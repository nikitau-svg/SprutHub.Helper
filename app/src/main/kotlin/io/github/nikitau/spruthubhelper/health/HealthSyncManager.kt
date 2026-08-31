package io.github.nikitau.spruthubhelper.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.sprut.VirtualDeviceInspection
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.sprut.VirtualFieldSpec
import io.github.nikitau.spruthubhelper.sprut.bindingMatchesFields
import io.github.nikitau.spruthubhelper.sprut.healthVirtualFields
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val HEALTH_NOT_CONFIGURED = "Не настроено"
private const val HEALTH_CONFIGURED = "Устройство здоровья настроено"

class HealthSyncManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val reader: HealthReader,
    private val virtualDevice: VirtualHealthDeviceManager,
    private val scope: CoroutineScope,
) {
    private val runtime = MutableStateFlow(HealthRuntimeState())
    private val _permissionRequests = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)
    private val syncMutex = Mutex()
    private val deviceMutationMutex = Mutex()

    val permissionRequests = _permissionRequests
    val state: StateFlow<HealthUiState> = combine(
        settings.selectedHealthMetrics,
        settings.healthBinding,
        settings.healthEnabled,
        settings.lastHealthSync,
        runtime,
    ) { metrics, binding, enabled, lastSync, live ->
        HealthUiState(
            available = live.available,
            backgroundReadAvailable = live.backgroundReadAvailable,
            backgroundReadGranted = live.backgroundReadAvailable &&
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in live.grantedPermissions,
            grantedPermissions = live.grantedPermissions,
            selectedMetrics = metrics,
            allSelectedPermissionsGranted = reader.permissions(metrics).all { it in live.grantedPermissions },
            binding = binding,
            configurationMatches = binding == null || bindingMatchesFields(binding, healthVirtualFields(metrics)),
            enabled = enabled,
            lastSyncEpochMs = lastSync,
            syncing = live.syncing,
            deviceInspection = live.deviceInspection,
            message = resolveHealthMessage(binding, live.message),
        )
    }.stateIn(scope, SharingStarted.Eagerly, HealthUiState())

    init {
        scope.launch {
            refreshPermissions()
            deviceMutationMutex.withLock {
                syncMutex.withLock {
                    if (settings.healthBinding.first() == null) recoverExistingOnStartup()
                }
            }
            refreshDeviceInspection()
            settings.healthEnabled.distinctUntilChanged().collect { enabled ->
                if (enabled) schedule() else cancel()
            }
        }
    }

    fun requestPermissions() {
        scope.launch {
            if (!reader.isAvailable()) {
                runtime.value = runtime.value.copy(available = false, message = "Health Connect недоступен")
                return@launch
            }
            val selected = settings.selectedHealthMetrics.first()
            val permissions = reader.permissions(selected).toMutableSet()
            if (backgroundReadAvailable()) permissions += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
            _permissionRequests.emit(permissions)
        }
    }

    fun refreshRuntimeStatus() {
        scope.launch { refreshPermissions() }
    }

    suspend fun onPermissionsChanged() {
        refreshPermissions()
        if (settings.healthEnabled.first()) {
            if (backgroundReadGranted()) {
                schedule()
                syncNow()
            } else {
                settings.setHealthEnabled(false)
                cancel()
                runtime.value = runtime.value.copy(
                    message = "Фоновый доступ Health Connect отозван — оставлена ручная синхронизация",
                )
            }
        }
    }

    suspend fun saveSelectedMetrics(metrics: Set<HealthMetric>) {
        settings.saveHealthMetrics(metrics)
        refreshPermissions()
    }

    suspend fun createDevice(roomId: String): Result<HealthDeviceBinding> = runExclusiveDeviceMutation(
        progressMessage = "Создаю виртуальное устройство…",
        failureMessage = "Не удалось создать устройство",
    ) {
        check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
        check(reader.isAvailable()) { "Health Connect недоступен" }
        val selected = settings.selectedHealthMetrics.first()
        val missing = reader.permissions(selected) - reader.grantedPermissions()
        check(missing.isEmpty()) { "Сначала разрешите все выбранные показатели в Health Connect" }
        val binding = virtualDevice.createOrRecover(roomId, healthVirtualFields(selected))
        val published = publishAndPersist(binding)
        refreshDeviceInspection()
        val canRunInBackground = backgroundReadGranted()
        settings.setHealthEnabled(canRunInBackground)
        if (canRunInBackground) schedule() else cancel()
        runtime.value = runtime.value.copy(
            syncing = false,
            message = if (canRunInBackground) {
                if (published > 0) "Устройство здоровья готово, фон включён"
                else "Устройство готово; в Health Connect пока нет записей"
            } else {
                "Устройство готово; разрешите фоновое чтение для автообновления"
            },
        )
        binding
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> {
        val event = if (fromBackground) "Фоновая синхронизация здоровья" else "Ручная синхронизация здоровья"
        if (fromBackground && !settings.healthEnabled.first()) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.SYNC,
                event = event,
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "Фоновое чтение здоровья выключено или разрешение отозвано",
            )
            return Result.success(Unit)
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.SYNC,
            event = event,
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("источник" to if (fromBackground) "фон" else "экран приложения"),
        )
        return syncMutex.withLock {
            runCatching {
                val selected = settings.selectedHealthMetrics.first()
                val selectedFields = healthVirtualFields(selected)
                val binding = settings.healthBinding.first()
                    ?: virtualDevice.recoverExisting(selectedFields)
                    ?: error("Сначала создайте устройство здоровья в SprutHub")
                check(bindingMatchesFields(binding, selectedFields)) {
                    "Выбор показателей изменён. Примените новый состав устройства здоровья в SprutHub"
                }
                runtime.value = runtime.value.copy(syncing = true, message = "Читаю Health Connect…")
                val published = publishAndPersist(
                    initialBinding = binding,
                    fields = selectedFields,
                    createIfMissing = !fromBackground,
                )
                refreshDeviceInspection()
                runtime.value = runtime.value.copy(
                    syncing = false,
                    message = if (published > 0) {
                        "Здоровье синхронизировано локально"
                    } else {
                        "В Health Connect пока нет записей; нулевые значения не отправлялись"
                    },
                )
                Log.i(LOG_TAG, "Health sync completed")
                Unit
            }.onSuccess {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = event,
                    outcome = DiagnosticOutcome.SUCCESS,
                )
            }.onFailure {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = event,
                    outcome = DiagnosticOutcome.FAILED,
                    reason = it.message,
                )
                runtime.value = runtime.value.copy(
                    syncing = false,
                    message = it.message ?: "Ошибка синхронизации здоровья",
                )
                Log.e(LOG_TAG, "Health sync failed", it)
            }
        }
    }

    suspend fun recreateDevice(selectedOverride: Set<HealthMetric>? = null): Result<HealthDeviceBinding> =
        runExclusiveDeviceMutation(
            progressMessage = "Пересоздаю устройство здоровья…",
            failureMessage = "Не удалось пересоздать устройство здоровья",
        ) {
            val current = settings.healthBinding.first()
                ?: error("Сначала создайте устройство здоровья в SprutHub")
            val selected = selectedOverride ?: settings.selectedHealthMetrics.first()
            check(reader.isAvailable()) { "Health Connect недоступен" }
            val missing = reader.permissions(selected) - reader.grantedPermissions()
            check(missing.isEmpty()) { "Сначала разрешите все выбранные показатели в Health Connect" }
            if (selectedOverride != null) settings.saveHealthMetrics(selectedOverride)
            val binding = virtualDevice.recreate(
                binding = current,
                roomId = current.roomId,
                fields = healthVirtualFields(selected),
            )
            val published = publishAndPersist(binding)
            refreshDeviceInspection()
            runtime.value = runtime.value.copy(
                syncing = false,
                message = if (published > 0) {
                    "Состав устройства здоровья обновлён"
                } else {
                    "Устройство пересоздано; в Health Connect пока нет записей"
                },
            )
            binding
        }

    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            check(settings.healthBinding.first() != null) { "Сначала создайте устройство здоровья в SprutHub" }
            check(backgroundReadAvailable()) { "Эта версия Health Connect не поддерживает фоновое чтение" }
            check(backgroundReadGranted()) { "Разрешите фоновое чтение в Health Connect" }
        }
        settings.setHealthEnabled(enabled)
        if (enabled) schedule() else cancel()
        runtime.value = runtime.value.copy(
            message = if (enabled) "Фоновая синхронизация включена" else "Оставлена ручная синхронизация",
        )
    }

    suspend fun revokeAllPermissions() {
        settings.setHealthEnabled(false)
        cancel()
        reader.revokeAllPermissions()
        refreshPermissions()
        runtime.value = runtime.value.copy(
            message = "Доступ Health Connect отозван; Android может обновить экран разрешений после перезапуска",
        )
    }

    suspend fun refreshPermissions() {
        val available = reader.isAvailable()
        runtime.value = runtime.value.copy(
            available = available,
            backgroundReadAvailable = if (available) backgroundReadAvailable() else false,
            grantedPermissions = if (available) reader.grantedPermissions() else emptySet(),
        )
    }

    private suspend fun backgroundReadAvailable(): Boolean {
        if (!reader.isAvailable()) return false
        return HealthConnectClient.getOrCreate(context).features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
        ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }

    private suspend fun backgroundReadGranted(): Boolean = backgroundReadAvailable() &&
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in reader.grantedPermissions()

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.MINUTES,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private suspend fun publishAndPersist(
        initialBinding: HealthDeviceBinding,
        fields: List<VirtualFieldSpec>? = null,
        createIfMissing: Boolean = false,
    ): Int {
        val selected = settings.selectedHealthMetrics.first()
        val publishFields = fields ?: healthVirtualFields(selected)
        val readings = reader.read(selected)
        if (readings.isEmpty()) {
            val verifiedBinding = virtualDevice.ensureBinding(
                binding = initialBinding,
                fields = publishFields,
                createIfMissing = createIfMissing,
            )
            settings.saveHealthBinding(verifiedBinding)
            settings.markHealthSynced()
            return 0
        }
        // Health traffic is intentionally pinned to the exact LAN endpoint. If IDs changed,
        // rebuild the binding by the virtual accessory name and retry once.
        val verifiedBinding = runCatching {
            virtualDevice.publish(initialBinding, readings, publishFields, createIfMissing)
        }.recoverCatching { firstError ->
            val recovered = virtualDevice.ensureBinding(initialBinding, publishFields, createIfMissing)
            runCatching { virtualDevice.publish(recovered, readings, publishFields) }
                .getOrElse { secondError ->
                    secondError.addSuppressed(firstError)
                    throw secondError
                }
        }.getOrThrow()
        settings.saveHealthBinding(verifiedBinding)
        settings.markHealthSynced()
        return readings.keys.count { key -> verifiedBinding.targets.any { it.key == key } }
    }

    private suspend fun recoverExistingOnStartup() {
        val fields = healthVirtualFields(settings.selectedHealthMetrics.first())
        runCatching { virtualDevice.recoverExisting(fields) }
            .onSuccess { binding ->
                if (binding != null) {
                    runCatching { publishAndPersist(binding) }
                        .onSuccess { published ->
                            val canRunInBackground = backgroundReadGranted()
                            settings.setHealthEnabled(canRunInBackground)
                            runtime.value = runtime.value.copy(
                                syncing = false,
                                message = if (published > 0) {
                                    "Найдено и проверено существующее устройство здоровья"
                                } else {
                                    "Устройство найдено; в Health Connect пока нет записей"
                                },
                            )
                            Log.i(LOG_TAG, "Existing health accessory recovered and verified on startup")
                        }
                        .onFailure { error ->
                            runtime.value = runtime.value.copy(
                                syncing = false,
                                message = error.message ?: "Не удалось проверить устройство здоровья",
                            )
                            Log.e(LOG_TAG, "Recovered health accessory failed verification", error)
                        }
                }
            }
            .onFailure { error ->
                Log.i(LOG_TAG, "No recoverable health accessory on startup: ${error.message}")
            }
    }

    private suspend fun refreshDeviceInspection() {
        val binding = settings.healthBinding.first()
        runCatching { virtualDevice.inspect(binding) }
            .onSuccess { inspection -> runtime.value = runtime.value.copy(deviceInspection = inspection) }
            .onFailure { error -> Log.w(LOG_TAG, "Virtual health duplicate inspection failed", error) }
    }

    private suspend fun <T> runExclusiveDeviceMutation(
        progressMessage: String,
        failureMessage: String,
        block: suspend () -> T,
    ): Result<T> {
        if (!deviceMutationMutex.tryLock()) {
            return Result.failure(IllegalStateException("Изменение устройства уже выполняется — дождитесь завершения"))
        }
        runtime.value = runtime.value.copy(syncing = true, message = progressMessage)
        val result = try {
            syncMutex.withLock { runCatching { block() } }
        } finally {
            deviceMutationMutex.unlock()
        }
        return result.onFailure { error ->
            runtime.value = runtime.value.copy(syncing = false, message = error.message ?: failureMessage)
        }
    }

    private companion object {
        const val WORK_NAME = "spruthub_health_sync"
        const val LOG_TAG = "SprutHubHealth"
    }
}

data class HealthUiState(
    val available: Boolean = false,
    val backgroundReadAvailable: Boolean = false,
    val backgroundReadGranted: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val selectedMetrics: Set<HealthMetric> = SettingsRepository.DEFAULT_HEALTH_METRICS,
    val allSelectedPermissionsGranted: Boolean = false,
    val binding: HealthDeviceBinding? = null,
    val configurationMatches: Boolean = true,
    val enabled: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val syncing: Boolean = false,
    val deviceInspection: VirtualDeviceInspection? = null,
    val message: String = HEALTH_NOT_CONFIGURED,
)

private data class HealthRuntimeState(
    val available: Boolean = false,
    val backgroundReadAvailable: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val deviceInspection: VirtualDeviceInspection? = null,
    val message: String = HEALTH_NOT_CONFIGURED,
)

internal fun resolveHealthMessage(binding: HealthDeviceBinding?, runtimeMessage: String): String =
    if (binding != null && runtimeMessage == HEALTH_NOT_CONFIGURED) HEALTH_CONFIGURED else runtimeMessage
