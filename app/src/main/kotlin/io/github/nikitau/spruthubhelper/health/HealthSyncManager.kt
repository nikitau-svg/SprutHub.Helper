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
                runCatching { virtualDevice.recoverExisting() }
                    .onSuccess { binding ->
                        if (binding != null) {
                            val canRunInBackground = backgroundReadGranted()
                            settings.setHealthEnabled(canRunInBackground)
                            runtime.value = runtime.value.copy(
                                message = "Найдено существующее устройство здоровья",
                            )
                            Log.i(LOG_TAG, "Existing health accessory recovered on startup")
                            syncNow()
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
        val binding = virtualDevice.createOrRecover(roomId)
        val canRunInBackground = backgroundReadGranted()
        settings.setHealthEnabled(canRunInBackground)
        if (canRunInBackground) schedule() else cancel()
        runtime.value = runtime.value.copy(
            syncing = false,
            message = if (canRunInBackground) {
                "Устройство здоровья готово, фон включён"
            } else {
                "Устройство готово; разрешите фоновое чтение для автообновления"
            },
        )
        syncNow().getOrThrow()
        binding
    }.onFailure {
        runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Не удалось создать устройство")
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> {
        if (fromBackground && !settings.healthEnabled.first()) return Result.success(Unit)
        return runCatching {
            val binding = settings.healthBinding.first()
                ?: virtualDevice.recoverExisting()
                ?: error("Сначала создайте устройство здоровья в SprutHub")
            runtime.value = runtime.value.copy(syncing = true, message = "Читаю Health Connect…")
            val readings = reader.read(settings.selectedHealthMetrics.first())
            check(readings.isNotEmpty()) { "Нет разрешённых данных для синхронизации" }
            // VirtualHealthDeviceManager pins all health traffic to the exact LAN endpoint.
            virtualDevice.publish(binding, readings)
            settings.markHealthSynced()
            runtime.value = runtime.value.copy(syncing = false, message = "Здоровье синхронизировано локально")
            Log.i(LOG_TAG, "Health sync completed")
        }.onFailure {
            runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Ошибка синхронизации здоровья")
            Log.e(LOG_TAG, "Health sync failed", it)
        }
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
