package io.github.nikitau.spruthubhelper.health

import android.content.Context
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
            grantedPermissions = live.grantedPermissions,
            selectedMetrics = metrics,
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
            schedule()
            syncNow()
        }
    }

    suspend fun saveSelectedMetrics(metrics: Set<HealthMetric>) {
        settings.saveHealthMetrics(metrics)
        refreshPermissions()
    }

    suspend fun createDevice(roomId: String): Result<HealthDeviceBinding> = runCatching {
        check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
        runtime.value = runtime.value.copy(syncing = true, message = "Создаю виртуальное устройство…")
        val binding = virtualDevice.createOrRecover(roomId)
        settings.setHealthEnabled(true)
        schedule()
        runtime.value = runtime.value.copy(syncing = false, message = "Устройство здоровья готово")
        syncNow()
        binding
    }.onFailure {
        runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Не удалось создать устройство")
    }

    suspend fun syncNow(): Result<Unit> = runCatching {
        val binding = settings.healthBinding.first() ?: error("Сначала создайте устройство здоровья в SprutHub")
        check(settings.healthEnabled.first()) { "Фоновая синхронизация выключена" }
        runtime.value = runtime.value.copy(syncing = true, message = "Читаю Health Connect…")
        val readings = reader.read(settings.selectedHealthMetrics.first())
        check(readings.isNotEmpty()) { "Нет разрешённых данных для синхронизации" }
        // VirtualHealthDeviceManager enforces ConnectionMode.LOCAL for all medical data.
        virtualDevice.publish(binding, readings)
        settings.markHealthSynced()
        runtime.value = runtime.value.copy(syncing = false, message = "Здоровье синхронизировано локально")
    }.onFailure {
        runtime.value = runtime.value.copy(syncing = false, message = it.message ?: "Ошибка синхронизации здоровья")
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.setHealthEnabled(enabled)
        if (enabled) schedule() else cancel()
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
    }
}

data class HealthUiState(
    val available: Boolean = false,
    val backgroundReadAvailable: Boolean = false,
    val grantedPermissions: Set<String> = emptySet(),
    val selectedMetrics: Set<HealthMetric> = SettingsRepository.DEFAULT_HEALTH_METRICS,
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
