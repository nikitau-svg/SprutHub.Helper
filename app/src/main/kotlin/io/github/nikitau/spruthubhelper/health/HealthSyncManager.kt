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
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.sprut.healthVirtualFields
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HealthSyncManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val reader: HealthReader,
    private val virtualDevice: VirtualHealthDeviceManager,
    private val scope: CoroutineScope,
) {
    private val runtime = MutableStateFlow(HealthRuntimeState())
    private val _permissionRequests = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)

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
            configurationMatches = binding == null ||
                binding.targets.map { it.key }.toSet() == metrics.map { it.name }.toSet(),
            enabled = enabled,
            lastSyncEpochMs = lastSync,
            syncing = live.syncing,
            message = live.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HealthUiState())

    init {
        scope.launch {
            refreshPermissions()
            if (settings.healthBinding.first() == null) {
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
            settings.healthEnabled.collect { enabled ->
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

    suspend fun createDevice(roomId: String): Result<HealthDeviceBinding> = runCatching {
        check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
        check(reader.isAvailable()) { "Health Connect недоступен" }
        val selected = settings.selectedHealthMetrics.first()
        val missing = reader.permissions(selected) - reader.grantedPermissions()
        check(missing.isEmpty()) { "Сначала разрешите все выбранные показатели в Health Connect" }
        runtime.value = runtime.value.copy(syncing = true, message = "Создаю виртуальное устройство…")
        val binding = virtualDevice.createOrRecover(roomId, healthVirtualFields(selected))
        val published = publishAndPersist(binding)
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
    }.onFailure {
        runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Не удалось создать устройство")
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> {
        if (fromBackground && !settings.healthEnabled.first()) return Result.success(Unit)
        return runCatching {
            val selected = settings.selectedHealthMetrics.first()
            val binding = settings.healthBinding.first()
                ?: virtualDevice.recoverExisting(healthVirtualFields(selected))
                ?: error("Сначала создайте устройство здоровья в SprutHub")
            runtime.value = runtime.value.copy(syncing = true, message = "Читаю Health Connect…")
            val published = publishAndPersist(binding)
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
        }.onFailure {
            runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Ошибка синхронизации здоровья")
            Log.e(LOG_TAG, "Health sync failed", it)
        }
    }

    suspend fun recreateDevice(): Result<HealthDeviceBinding> = runCatching {
        val current = settings.healthBinding.first()
            ?: error("Сначала создайте устройство здоровья в SprutHub")
        val selected = settings.selectedHealthMetrics.first()
        runtime.value = runtime.value.copy(syncing = true, message = "Пересоздаю устройство здоровья…")
        val binding = virtualDevice.recreate(
            binding = current,
            roomId = current.roomId,
            fields = healthVirtualFields(selected),
        )
        val published = publishAndPersist(binding)
        runtime.value = runtime.value.copy(
            syncing = false,
            message = if (published > 0) {
                "Состав устройства здоровья обновлён"
            } else {
                "Устройство пересоздано; в Health Connect пока нет записей"
            },
        )
        binding
    }.onFailure { error ->
        runtime.value = runtime.value.copy(
            syncing = false,
            message = error.message ?: "Не удалось пересоздать устройство здоровья",
        )
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

    private suspend fun publishAndPersist(initialBinding: HealthDeviceBinding): Int {
        val selected = settings.selectedHealthMetrics.first()
        val readings = reader.read(selected)
        val boundMetrics = initialBinding.targets
            .mapNotNull { target -> runCatching { HealthMetric.valueOf(target.key) }.getOrNull() }
            .toSet()
            .ifEmpty { selected }
        val fields = healthVirtualFields(boundMetrics)
        if (readings.isEmpty()) {
            settings.saveHealthBinding(initialBinding)
            settings.markHealthSynced()
            return 0
        }
        // Health traffic is intentionally pinned to the exact LAN endpoint. If IDs changed,
        // rebuild the binding by the virtual accessory name and retry once.
        val verifiedBinding = runCatching {
            virtualDevice.publish(initialBinding, readings, fields)
        }.recoverCatching { firstError ->
            val recovered = virtualDevice.recoverExisting(fields) ?: throw firstError
            runCatching { virtualDevice.publish(recovered, readings, fields) }
                .getOrElse { secondError ->
                    secondError.addSuppressed(firstError)
                    throw secondError
                }
        }.getOrThrow()
        settings.saveHealthBinding(verifiedBinding)
        settings.markHealthSynced()
        return readings.keys.count { key -> verifiedBinding.targets.any { it.key == key } }
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
    val message: String = "Не настроено",
)

private data class HealthRuntimeState(
    val available: Boolean = false,
    val backgroundReadAvailable: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val syncing: Boolean = false,
    val message: String = "Не настроено",
)
