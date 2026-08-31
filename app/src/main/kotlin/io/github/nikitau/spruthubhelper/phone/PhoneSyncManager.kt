package io.github.nikitau.spruthubhelper.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSensorAccess
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.PhoneSyncSettings
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticCategory
import io.github.nikitau.spruthubhelper.diagnostics.DiagnosticOutcome
import io.github.nikitau.spruthubhelper.health.HealthReading
import io.github.nikitau.spruthubhelper.sprut.HeartbeatProtectionReport
import io.github.nikitau.spruthubhelper.sprut.HeartbeatProtectionStatus
import io.github.nikitau.spruthubhelper.sprut.SprutHeartbeatScenarioManager
import io.github.nikitau.spruthubhelper.sprut.VirtualDeviceInspection
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val heartbeatScenario: SprutHeartbeatScenarioManager,
    private val scope: CoroutineScope,
) {
    private val runtime = MutableStateFlow(PhoneRuntimeState())
    private val syncMutex = Mutex()
    private val deviceMutationMutex = Mutex()
    private var lastProtectionCheckElapsedMs = 0L

    val state: StateFlow<PhoneUiState> = combine(
        settings.selectedPhoneSensors,
        settings.phoneBinding,
        settings.phoneSyncSettings,
        settings.lastPhoneSync,
        runtime,
    ) { sensors, binding, syncSettings, lastSync, live ->
        val currentReadings = runCatching { reader.read(sensors) }.getOrDefault(emptyMap())
        PhoneUiState(
            selectedSensors = sensors,
            currentReadings = sensors.mapNotNull { sensor ->
                currentReadings[sensor.name]?.let { reading -> sensor to reading }
            }.toMap(),
            missingSensorAccesses = missingPhoneSensorAccesses(context, sensors),
            unsupportedSensors = unsupportedPhoneSensors(sensors),
            notificationPolicyAccessGranted = phoneSensorAccessGranted(
                context,
                PhoneSensorAccess.NOTIFICATION_POLICY,
            ),
            binding = binding,
            configurationMatches = binding == null || bindingMatchesFields(binding, phoneVirtualFields(sensors)),
            syncSettings = syncSettings,
            lastSyncEpochMs = lastSync,
            syncing = live.syncing,
            monitorRunning = live.monitorRunning,
            notificationPermissionGranted = notificationPermissionGranted(),
            batteryOptimizationIgnored = batteryOptimizationIgnored(),
            deviceInspection = live.deviceInspection,
            heartbeatProtection = live.heartbeatProtection,
            reliabilityChecking = live.reliabilityChecking,
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
                runCatching {
                    virtualDevice.recoverExisting(
                        fields = phoneVirtualFields(selected),
                        allowIncomplete = true,
                    )
                }
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
            refreshDeviceInspection()
            settings.phoneBinding.first()?.let { binding ->
                if (settings.phoneSyncSettings.first().enabled) {
                    refreshReliabilityInternal(binding, repair = true, force = true)
                } else {
                    pauseReliabilityInternal(binding)
                }
            }
            settings.phoneSyncSettings.distinctUntilChanged().collect { syncSettings ->
                if (syncSettings.enabled) {
                    if (settings.phoneMonitoringStarted.first() == null) {
                        settings.ensurePhoneMonitoringStarted()
                    }
                    schedule()
                } else {
                    cancel()
                }
                if (syncSettings.enabled && syncSettings.watchdogEnabled) {
                    PhoneSyncWatchdog.schedule(context)
                } else {
                    PhoneSyncWatchdog.cancel(context)
                }
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

    suspend fun createDevice(roomId: String): Result<HealthDeviceBinding> = runExclusiveDeviceMutation(
        progressMessage = "Создаю устройство телефона…",
        failureMessage = "Не удалось создать устройство телефона",
    ) {
        check(roomId.isNotBlank()) { "Выберите комнату SprutHub" }
        val selected = settings.selectedPhoneSensors.first()
        ensurePhoneSensorsReady(selected)
        val binding = virtualDevice.createOrRecover(roomId, phoneVirtualFields(selected))
        refreshReliabilityInternal(binding, repair = true, force = true)
        val publishResult = publishAndPersist(binding)
        if (heartbeatBindingChanged(binding, publishResult.binding)) {
            refreshReliabilityInternal(publishResult.binding, repair = true, force = true)
        }
        refreshDeviceInspection()
        settings.setPhoneEnabled(true)
        schedule()
        refreshLiveMonitorAfterBindingChange()
        runtime.update {
            it.copy(
                syncing = false,
                message = "Устройство телефона готово: обновлено ${publishResult.publishedFields} полей",
            )
        }
        publishResult.binding
    }

    suspend fun syncNow(fromBackground: Boolean = false): Result<Unit> {
        if (fromBackground && !settings.phoneSyncSettings.first().enabled) {
            AppGraph.diagnostics.record(
                category = DiagnosticCategory.SYNC,
                event = "Синхронизация телефона",
                outcome = DiagnosticOutcome.SKIPPED,
                reason = "фоновая синхронизация выключена",
            )
            return Result.success(Unit)
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.SYNC,
            event = "Синхронизация телефона",
            outcome = DiagnosticOutcome.STARTED,
            details = mapOf("источник" to if (fromBackground) "фон" else "действие пользователя"),
        )
        return syncMutex.withLock {
            runCatching {
                val selected = settings.selectedPhoneSensors.first()
                ensurePhoneSensorsReady(selected)
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
                if (settings.phoneSyncSettings.first().enabled) {
                    refreshReliabilityInternal(
                        binding = binding,
                        repair = true,
                        force = !fromBackground,
                    )
                }
                val publishResult = publishAndPersist(
                    initialBinding = binding,
                    fields = selectedFields,
                    createIfMissing = !fromBackground,
                )
                if (
                    settings.phoneSyncSettings.first().enabled &&
                    heartbeatBindingChanged(binding, publishResult.binding)
                ) {
                    refreshReliabilityInternal(publishResult.binding, repair = true, force = true)
                }
                runtime.update {
                    it.copy(
                        syncing = false,
                        message = "Телефон синхронизирован: обновлено ${publishResult.publishedFields} полей",
                    )
                }
                Log.i(LOG_TAG, "Phone sync completed with ${publishResult.publishedFields} fields")
                Unit
            }.onSuccess {
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = "Синхронизация телефона",
                    outcome = DiagnosticOutcome.SUCCESS,
                    details = mapOf("источник" to if (fromBackground) "фон" else "действие пользователя"),
                )
            }.onFailure { error ->
                runtime.update { it.copy(syncing = false, message = error.message ?: "Ошибка синхронизации телефона") }
                Log.e(LOG_TAG, "Phone sync failed", error)
                AppGraph.diagnostics.record(
                    category = DiagnosticCategory.SYNC,
                    event = "Синхронизация телефона",
                    outcome = DiagnosticOutcome.FAILED,
                    reason = error.message ?: "неизвестная ошибка",
                    details = mapOf("источник" to if (fromBackground) "фон" else "действие пользователя"),
                )
            }
        }
    }

    suspend fun recreateDevice(selectedOverride: Set<PhoneSensor>? = null): Result<HealthDeviceBinding> =
        runExclusiveDeviceMutation(
            progressMessage = "Пересоздаю устройство телефона…",
            failureMessage = "Не удалось пересоздать устройство телефона",
        ) {
            val current = settings.phoneBinding.first()
                ?: error("Сначала создайте устройство телефона в SprutHub")
            val selected = selectedOverride ?: settings.selectedPhoneSensors.first()
            ensurePhoneSensorsReady(selected)
            if (selectedOverride != null) settings.savePhoneSensors(selectedOverride)
            val binding = virtualDevice.recreate(
                binding = current,
                roomId = current.roomId,
                fields = phoneVirtualFields(selected),
            )
            if (settings.phoneSyncSettings.first().enabled) {
                refreshReliabilityInternal(binding, repair = true, force = true)
            } else {
                pauseReliabilityInternal(binding)
            }
            val publishResult = publishAndPersist(binding)
            if (heartbeatBindingChanged(binding, publishResult.binding)) {
                if (settings.phoneSyncSettings.first().enabled) {
                    refreshReliabilityInternal(publishResult.binding, repair = true, force = true)
                } else {
                    pauseReliabilityInternal(publishResult.binding)
                }
            }
            refreshDeviceInspection()
            refreshLiveMonitorAfterBindingChange()
            runtime.update {
                it.copy(
                    syncing = false,
                    message = "Состав устройства телефона обновлён: ${publishResult.publishedFields} полей",
                )
            }
            publishResult.binding
        }

    suspend fun setEnabled(enabled: Boolean) {
        val binding = settings.phoneBinding.first()
        if (enabled) {
            check(binding != null) { "Сначала создайте устройство телефона в SprutHub" }
            ensurePhoneSensorsReady(settings.selectedPhoneSensors.first())
            val syncSettings = settings.phoneSyncSettings.first()
            if (syncSettings.mode == PhoneSyncMode.LIVE) {
                check(notificationPermissionGranted()) {
                    "Для постоянного подключения разрешите уведомления"
                }
            }
        }
        val protection = binding?.let {
            if (enabled) {
                refreshReliabilityInternal(it, repair = true, force = true)
            } else {
                pauseReliabilityInternal(it)
            }
        }
        settings.setPhoneEnabled(enabled)
        if (enabled) {
            schedule()
            val syncSettings = settings.phoneSyncSettings.first()
            if (syncSettings.watchdogEnabled) PhoneSyncWatchdog.schedule(context)
            if (syncSettings.mode == PhoneSyncMode.LIVE) {
                PhoneMonitorService.start(context)
            }
        } else {
            cancel()
            PhoneSyncWatchdog.cancel(context)
            PhoneMonitorService.stop(context)
        }
        runtime.update {
            it.copy(
                message = when {
                    protection?.status == HeartbeatProtectionStatus.ERROR ->
                        "Синхронизация ${if (enabled) "включена" else "выключена"}, но SprutHub не подтвердил защитный сценарий"
                    enabled -> "Фоновая синхронизация телефона включена"
                    else -> "Фоновая синхронизация и тревога SprutHub приостановлены"
                },
            )
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

    suspend fun setWatchdogEnabled(enabled: Boolean) {
        if (enabled) {
            check(notificationPermissionGranted()) {
                "Для локального предупреждения разрешите уведомления"
            }
        }
        settings.setPhoneWatchdogEnabled(enabled)
        val backgroundEnabled = settings.phoneSyncSettings.first().enabled
        if (enabled && backgroundEnabled) {
            PhoneSyncWatchdog.schedule(context)
        } else {
            PhoneSyncWatchdog.cancel(context)
        }
        runtime.update {
            it.copy(
                message = if (enabled) {
                    "Локальное предупреждение о застывшей синхронизации включено"
                } else {
                    "Локальное предупреждение выключено"
                },
            )
        }
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

    suspend fun checkAndRepairReliability(): Result<HeartbeatProtectionReport> = runCatching {
        val binding = settings.phoneBinding.first()
            ?: error("Сначала создайте устройство телефона в SprutHub")
        runtime.update { it.copy(reliabilityChecking = true, message = "Проверяю защиту и дубли…") }
        val report = if (settings.phoneSyncSettings.first().enabled) {
            refreshReliabilityInternal(binding, repair = true, force = true)
        } else {
            pauseReliabilityInternal(binding)
        }
        runtime.update {
            it.copy(
                reliabilityChecking = false,
                message = report.message,
            )
        }
        report
    }.onFailure { error ->
        runtime.update {
            it.copy(
                reliabilityChecking = false,
                message = error.message ?: "Не удалось проверить защиту синхронизации",
            )
        }
    }

    private suspend fun refreshReliabilityInternal(
        binding: HealthDeviceBinding,
        repair: Boolean,
        force: Boolean,
    ): HeartbeatProtectionReport {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProtectionCheckElapsedMs < PROTECTION_CHECK_INTERVAL_MS) {
            return runtime.value.heartbeatProtection
        }
        val inspection = runCatching { virtualDevice.inspect(binding) }
            .onFailure { Log.w(LOG_TAG, "Virtual phone duplicate inspection failed", it) }
            .getOrNull()
        val report = runCatching {
            if (repair) heartbeatScenario.ensure(binding) else heartbeatScenario.inspect(binding)
        }.getOrElse { error ->
            Log.w(LOG_TAG, "SprutHub heartbeat protection check failed", error)
            HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.ERROR,
                message = error.message ?: "SprutHub не подтвердил служебный сценарий",
            )
        }
        lastProtectionCheckElapsedMs = now
        runtime.update {
            it.copy(
                deviceInspection = inspection ?: it.deviceInspection,
                heartbeatProtection = report,
            )
        }
        AppGraph.diagnostics.record(
            category = DiagnosticCategory.SYNC,
            event = "Защита синхронизации телефона",
            outcome = if (report.ready) DiagnosticOutcome.SUCCESS else DiagnosticOutcome.FAILED,
            reason = report.message,
            details = buildMap {
                put("сценариев Helper", report.appOwnedScenarioCount.toString())
                put("дублей аксессуара", inspection?.duplicateCount?.toString() ?: "не проверено")
            },
        )
        return report
    }

    private suspend fun refreshDeviceInspection() {
        runCatching { virtualDevice.inspect(settings.phoneBinding.first()) }
            .onSuccess { inspection -> runtime.update { it.copy(deviceInspection = inspection) } }
            .onFailure { Log.w(LOG_TAG, "Virtual phone duplicate inspection failed", it) }
    }

    private suspend fun refreshLiveMonitorAfterBindingChange() {
        val syncSettings = settings.phoneSyncSettings.first()
        if (syncSettings.enabled && syncSettings.mode == PhoneSyncMode.LIVE) {
            PhoneMonitorService.refresh(context)
        }
    }

    private suspend fun <T> runExclusiveDeviceMutation(
        progressMessage: String,
        failureMessage: String,
        block: suspend () -> T,
    ): Result<T> {
        if (!deviceMutationMutex.tryLock()) {
            return Result.failure(IllegalStateException("Изменение устройства уже выполняется — дождитесь завершения"))
        }
        runtime.update { it.copy(syncing = true, message = progressMessage) }
        val result = try {
            syncMutex.withLock { runCatching { block() } }
        } finally {
            deviceMutationMutex.unlock()
        }
        return result.onFailure { error ->
            runtime.update { it.copy(syncing = false, message = error.message ?: failureMessage) }
        }
    }

    private suspend fun pauseReliabilityInternal(binding: HealthDeviceBinding): HeartbeatProtectionReport {
        val inspection = runCatching { virtualDevice.inspect(binding) }
            .onFailure { Log.w(LOG_TAG, "Virtual phone duplicate inspection failed", it) }
            .getOrNull()
        val report = runCatching { heartbeatScenario.pause(binding) }.getOrElse { error ->
            Log.w(LOG_TAG, "SprutHub heartbeat protection could not be paused", error)
            HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.ERROR,
                message = "Синхронизация выключена, но SprutHub не подтвердил остановку тревоги: " +
                    (error.message ?: "неизвестная ошибка"),
            )
        }
        lastProtectionCheckElapsedMs = SystemClock.elapsedRealtime()
        runtime.update {
            it.copy(
                deviceInspection = inspection ?: it.deviceInspection,
                heartbeatProtection = report,
            )
        }
        return report
    }

    private suspend fun publishAndPersist(
        initialBinding: HealthDeviceBinding,
        fields: List<VirtualFieldSpec>? = null,
        createIfMissing: Boolean = false,
    ): PhonePublishResult {
        val selected = settings.selectedPhoneSensors.first()
        ensurePhoneSensorsReady(selected)
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
        PhoneSyncWatchdog.onSyncSucceeded(context)
        return PhonePublishResult(
            binding = verifiedBinding,
            publishedFields = readings.keys.count { key -> verifiedBinding.targets.any { it.key == key } },
        )
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
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun ensurePhoneSensorsReady(sensors: Set<PhoneSensor>) {
        phoneSensorReadinessError(
            missingAccesses = missingPhoneSensorAccesses(context, sensors),
            unsupportedSensors = unsupportedPhoneSensors(sensors),
        )?.let(::error)
    }

    private fun batteryOptimizationIgnored(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    private companion object {
        const val WORK_NAME = "spruthub_phone_sync"
        const val LOG_TAG = "SprutHubPhone"
        // Slightly below WorkManager's 15-minute cadence so scheduler jitter
        // cannot postpone a missing-scenario check to the following cycle.
        const val PROTECTION_CHECK_INTERVAL_MS = 12 * 60 * 1_000L
    }
}

private data class PhonePublishResult(
    val binding: HealthDeviceBinding,
    val publishedFields: Int,
)

internal fun heartbeatBindingChanged(first: HealthDeviceBinding, second: HealthDeviceBinding): Boolean {
    fun identity(binding: HealthDeviceBinding): Triple<String, String, String>? = binding.targets
        .firstOrNull { it.key == PhoneSensor.SYNC_HEARTBEAT.name }
        ?.let { Triple(binding.accessoryId, it.serviceId, it.characteristicId) }
    return identity(first) != identity(second)
}

data class PhoneUiState(
    val selectedSensors: Set<PhoneSensor> = SettingsRepository.DEFAULT_PHONE_SENSORS,
    val currentReadings: Map<PhoneSensor, HealthReading> = emptyMap(),
    val missingSensorAccesses: Set<PhoneSensorAccess> = emptySet(),
    val unsupportedSensors: Set<PhoneSensor> = emptySet(),
    val notificationPolicyAccessGranted: Boolean = false,
    val binding: HealthDeviceBinding? = null,
    val configurationMatches: Boolean = true,
    val syncSettings: PhoneSyncSettings = PhoneSyncSettings(),
    val lastSyncEpochMs: Long? = null,
    val syncing: Boolean = false,
    val monitorRunning: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val deviceInspection: VirtualDeviceInspection? = null,
    val heartbeatProtection: HeartbeatProtectionReport = HeartbeatProtectionReport(),
    val reliabilityChecking: Boolean = false,
    val message: String = "Не настроено",
)

private data class PhoneRuntimeState(
    val syncing: Boolean = false,
    val monitorRunning: Boolean = false,
    val deviceInspection: VirtualDeviceInspection? = null,
    val heartbeatProtection: HeartbeatProtectionReport = HeartbeatProtectionReport(),
    val reliabilityChecking: Boolean = false,
    val message: String = "Не настроено",
    val statusRevision: Long = 0,
)
