package io.github.nikitau.spruthubhelper.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.PhoneSyncSettings
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import io.github.nikitau.spruthubhelper.sprut.VirtualFieldSpec
import io.github.nikitau.spruthubhelper.sprut.bindingMatchesFields
import io.github.nikitau.spruthubhelper.sprut.phoneVirtualFields
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PhoneSyncManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val reader: PhoneReader,
    private val virtualDevice: VirtualHealthDeviceManager,
    private val scope: CoroutineScope,
) {
    private val runtime = MutableStateFlow(PhoneRuntimeState())
    private val syncMutex = Mutex()

    val state: StateFlow<PhoneUiState> = combine(
        settings.selectedPhoneSensors,
        settings.phoneBinding,
        settings.phoneSyncSettings,
        settings.lastPhoneSync,
        runtime,
    ) { sensors, binding, syncSettings, lastSync, live ->
        PhoneUiState(
            selectedSensors = sensors,
            binding = binding,
            configurationMatches = binding == null ||
                binding.targets.map { it.key }.toSet() == sensors.map(PhoneSensor::name).toSet(),
            syncSettings = syncSettings,
            lastSyncEpochMs = lastSync,
            syncing = live.syncing,
            monitorRunning = live.monitorRunning,
            notificationPermissionGranted = notificationPermissionGranted(),
            batteryOptimizationIgnored = batteryOptimizationIgnored(),
            message = live.message,
        )
    }.stateIn(scope, SharingStarted.Eagerly, PhoneUiState())

    init {
        scope.launch {
            PhoneMonitorService.running.collect { running ->
                runtime.update { it.copy(monitorRunning = running) }
            }
        }
        scope.launch {
            if (settings.phoneBinding.first() == null) {
                val selected = settings.selectedPhoneSensors.first()
                runCatching { virtualDevice.recoverExisting(phoneVirtualFields(selected)) }
                    .onSuccess { binding ->
                        if (binding != null) {
                            settings.savePhoneBinding(binding)
                            runtime.update { it.copy(message = "Найдено существующее устройство телефона") }
                        }
                    }
                    .onFailure { error ->
                        Log.i(LOG_TAG, "No recoverable phone accessory on startup: ${error.message}")
                    }
            }
            settings.phoneSyncSettings.collect { syncSettings ->
                if (syncSettings.enabled) schedule() else cancel()
                if (!syncSettings.enabled || syncSettings.mode != PhoneSyncMode.LIVE) {
                    PhoneMonitorService.stop(context)
                }
            }
        }
    }

    suspend fun saveSelectedSensors(sensors: Set<PhoneSensor>) {
        settings.savePhoneSensors(sensors)
        runtime.update {
            it.copy(
                message = if (state.value.binding != null) {
                    "Выбор сохранён; состав устройства SprutHub отличается"
                } else {
                    "Показатели телефона сохранены"
                },
            )
        }
    }

    suspend fun createDevice(roomId: String): Result<HealthDeviceBinding> = runCatching {
        check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
        val selected = settings.selectedPhoneSensors.first()
        runtime.update { it.copy(syncing = true, message = "Создаю устройство телефона…") }
        val binding = virtualDevice.createOrRecover(roomId, phoneVirtualFields(selected))
        val published = publishAndPersist(binding)
        settings.setPhoneEnabled(true)
        schedule()
        runtime.update {
            it.copy(
                syncing = false,
                message = "Устройство телефона готово: обновлено $published полей",
            )
        }
        binding
    }.onFailure { error ->
        runtime.update { it.copy(syncing = false, message = error.message ?: "Не удалось создать устройство телефона") }
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> {
        if (fromBackground && !settings.phoneSyncSettings.first().enabled) return Result.success(Unit)
        return syncMutex.withLock {
            runCatching {
                val selected = settings.selectedPhoneSensors.first()
                val stored = settings.phoneBinding.first()
                val recoverySensors = stored?.targets
                    ?.mapNotNull { target -> runCatching { PhoneSensor.valueOf(target.key) }.getOrNull() }
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: selected
                val binding = stored
                    ?: virtualDevice.recoverExisting(phoneVirtualFields(recoverySensors))
                    ?: error("Сначала создайте устройство телефона в SprutHub")
                val selectedFields = phoneVirtualFields(selected)
                check(bindingMatchesFields(binding, selectedFields)) {
                    "Выбор показателей изменён. Примените новый состав устройства телефона в SprutHub"
                }
                runtime.update { it.copy(syncing = true, message = "Собираю данные телефона…") }
                val published = publishAndPersist(
                    initialBinding = binding,
                    fields = selectedFields,
                    createIfMissing = !fromBackground,
                )
                runtime.update {
                    it.copy(
                        syncing = false,
                        message = "Телефон синхронизирован: обновлено $published полей",
                    )
                }
                Log.i(LOG_TAG, "Phone sync completed with $published fields")
                Unit
            }.onFailure { error ->
                runtime.update { it.copy(syncing = false, message = error.message ?: "Ошибка синхронизации телефона") }
                Log.e(LOG_TAG, "Phone sync failed", error)
            }
        }
    }

    suspend fun recreateDevice(selectedOverride: Set<PhoneSensor>? = null): Result<HealthDeviceBinding> = runCatching {
        val current = settings.phoneBinding.first()
            ?: error("Сначала создайте устройство телефона в SprutHub")
        val selected = selectedOverride ?: settings.selectedPhoneSensors.first()
        if (selectedOverride != null) settings.savePhoneSensors(selectedOverride)
        runtime.update { it.copy(syncing = true, message = "Пересоздаю устройство телефона…") }
        val binding = virtualDevice.recreate(
            binding = current,
            roomId = current.roomId,
            fields = phoneVirtualFields(selected),
        )
        val published = publishAndPersist(binding)
        runtime.update {
            it.copy(
                syncing = false,
                message = "Состав устройства телефона обновлён: $published полей",
            )
        }
        binding
    }.onFailure { error ->
        runtime.update {
            it.copy(syncing = false, message = error.message ?: "Не удалось пересоздать устройство телефона")
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            check(settings.phoneBinding.first() != null) { "Сначала создайте устройство телефона в SprutHub" }
            val syncSettings = settings.phoneSyncSettings.first()
            if (syncSettings.mode == PhoneSyncMode.LIVE) {
                check(notificationPermissionGranted()) {
                    "Для постоянного подключения разрешите уведомления"
                }
            }
        }
        settings.setPhoneEnabled(enabled)
        if (enabled) {
            schedule()
            if (settings.phoneSyncSettings.first().mode == PhoneSyncMode.LIVE) {
                PhoneMonitorService.start(context)
            }
        } else {
            cancel()
            PhoneMonitorService.stop(context)
        }
        runtime.update {
            it.copy(message = if (enabled) "Фоновая синхронизация телефона включена" else "Фоновая синхронизация выключена")
        }
    }

    suspend fun setSyncMode(mode: PhoneSyncMode) {
        if (mode == PhoneSyncMode.LIVE) {
            check(notificationPermissionGranted()) {
                "Для постоянного подключения разрешите уведомления"
            }
        }
        settings.setPhoneSyncMode(mode)
        val enabled = settings.phoneSyncSettings.first().enabled
        if (enabled && mode == PhoneSyncMode.LIVE) {
            PhoneMonitorService.start(context)
        } else {
            PhoneMonitorService.stop(context)
        }
        runtime.update {
            it.copy(
                message = if (mode == PhoneSyncMode.LIVE) {
                    "Постоянное подключение включено"
                } else {
                    "Выбран сбалансированный режим"
                },
            )
        }
    }

    suspend fun setPollInterval(interval: PhonePollInterval) {
        settings.setPhonePollInterval(interval)
        PhoneMonitorService.refresh(context)
        runtime.update { it.copy(message = "Опрос в постоянном режиме: ${interval.title}") }
    }

    fun ensureLiveMonitor() {
        scope.launch { ensureLiveMonitorNow() }
    }

    suspend fun ensureLiveMonitorNow() {
        runCatching {
            val current = settings.phoneSyncSettings.first()
            if (current.enabled && current.mode == PhoneSyncMode.LIVE && notificationPermissionGranted()) {
                PhoneMonitorService.start(context)
            }
        }.onFailure { error ->
            runtime.update {
                it.copy(message = error.message ?: "Android не разрешил запустить постоянный режим")
            }
            Log.w(LOG_TAG, "Live monitor could not be restored", error)
        }
        refreshRuntimeStatus()
    }

    fun refreshRuntimeStatus() {
        runtime.update { it.copy(statusRevision = it.statusRevision + 1) }
    }

    private suspend fun publishAndPersist(
        initialBinding: HealthDeviceBinding,
        fields: List<VirtualFieldSpec>? = null,
        createIfMissing: Boolean = false,
    ): Int {
        val selected = settings.selectedPhoneSensors.first()
        val publishFields = fields ?: phoneVirtualFields(selected)
        val readings = reader.read(selected)
        check(readings.isNotEmpty()) { "Android не вернул выбранные данные телефона" }
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
        settings.savePhoneBinding(verifiedBinding)
        settings.markPhoneSynced()
        return readings.keys.count { key -> verifiedBinding.targets.any { it.key == key } }
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<PhoneSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
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

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun batteryOptimizationIgnored(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private companion object {
        const val WORK_NAME = "spruthub_phone_sync"
        const val LOG_TAG = "SprutHubPhone"
    }
}

data class PhoneUiState(
    val selectedSensors: Set<PhoneSensor> = SettingsRepository.DEFAULT_PHONE_SENSORS,
    val binding: HealthDeviceBinding? = null,
    val configurationMatches: Boolean = true,
    val syncSettings: PhoneSyncSettings = PhoneSyncSettings(),
    val lastSyncEpochMs: Long? = null,
    val syncing: Boolean = false,
    val monitorRunning: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val message: String = "Не настроено",
)

private data class PhoneRuntimeState(
    val syncing: Boolean = false,
    val monitorRunning: Boolean = false,
    val message: String = "Не настроено",
    val statusRevision: Long = 0,
)
