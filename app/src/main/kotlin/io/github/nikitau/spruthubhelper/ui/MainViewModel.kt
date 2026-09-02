package io.github.nikitau.spruthubhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.DiagnosticEvent
import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.HubPasswordUpdate
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.PanelItem
import io.github.nikitau.spruthubhelper.data.PanelItemSize
import io.github.nikitau.spruthubhelper.data.PhonePollInterval
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.TileAssignment
import io.github.nikitau.spruthubhelper.data.normalizeAndValidateHubConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    private val settings = AppGraph.settings
    private val repository = AppGraph.repository
    private val health = AppGraph.health
    private val phone = AppGraph.phone
    private val presence = AppGraph.presence
    private val _busy = MutableStateFlow(false)
    private val connectionWorkInProgress = AtomicBoolean(false)
    private val activeWorkCount = AtomicInteger(0)
    private val _notice = MutableStateFlow<String?>(null)
    private val _onboardingRequired = MutableStateFlow<Boolean?>(null)
    private val _tileAddRequests = MutableSharedFlow<TileAddRequest>(extraBufferCapacity = 1)
    private val _panelAddRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _coordinateResults = MutableSharedFlow<Pair<Double, Double>>(extraBufferCapacity = 1)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val notice: StateFlow<String?> = _notice.asStateFlow()
    val onboardingRequired: StateFlow<Boolean?> = _onboardingRequired.asStateFlow()
    val healthState = health.state
    val phoneState = phone.state
    val presenceState = presence.state
    val tileAddRequests = _tileAddRequests
    val panelAddRequests = _panelAddRequests
    val coordinateResults = _coordinateResults
    val healthPermissionRequests = health.permissionRequests
    val uiState: StateFlow<MainUiState> = combine(
        settings.config,
        repository.catalog,
        repository.connectionStatus,
        combine(
            repository.tileAssignments,
            repository.panelItems,
            repository.servicePresentations,
        ) { tiles, panel, presentations -> Triple(tiles, panel, presentations) },
        repository.diagnostics,
    ) { config, catalog, connection, androidItems, diagnostics ->
        MainUiState(
            config = config,
            catalog = catalog,
            connection = connection,
            assignments = androidItems.first,
            panelItems = androidItems.second,
            servicePresentations = androidItems.third,
            diagnostics = diagnostics,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        phone.ensureLiveMonitor()
        viewModelScope.launch {
            _onboardingRequired.value = runCatching {
                settings.prepareOnboardingForLaunch()
            }.getOrElse { false }
            val config = settings.currentConfig()
            val hasEndpoint = when (config.mode) {
                ConnectionMode.AUTO -> config.localUrl.isNotBlank() || config.cloudUrl.isNotBlank()
                ConnectionMode.LOCAL -> config.localUrl.isNotBlank()
                ConnectionMode.CLOUD -> config.cloudUrl.isNotBlank()
            }
            if (config.serial.isNotBlank() && hasEndpoint) {
                repository.refresh()
            }
        }
    }

    fun saveAndTestSettings(
        mode: ConnectionMode,
        localUrl: String,
        cloudUrl: String,
        serial: String,
        email: String,
        newLocalPassword: String,
        newCloudPassword: String,
    ) = launchWork(null, exclusiveGuard = connectionWorkInProgress) {
        val hadWorkingConnection = uiState.value.connection.phase == ConnectionPhase.CONNECTED_LOCAL ||
            uiState.value.connection.phase == ConnectionPhase.CONNECTED_CLOUD
        val candidate = buildConnectionSettingsCandidate(
            stored = settings.currentConfig(),
            mode = mode,
            localUrl = localUrl,
            cloudUrl = cloudUrl,
            serial = serial,
            email = email,
            newLocalPassword = newLocalPassword,
            newCloudPassword = newCloudPassword,
        )
        val verified = repository.verifyConfig(candidate.config) {
            settings.saveConfig(candidate.config, candidate.passwordUpdate)
        }
        if (verified.isFailure && hadWorkingConnection) {
            withContext(NonCancellable) {
                repository.refresh(forceConnection = true)
            }
        }
        val catalog = verified.getOrThrow()
        _notice.value = "Каталог перечитан: найдено ${catalog.controls.size} элементов. Новые устройства не создавались"
    }

    fun testConnection() = launchWork(null, exclusiveGuard = connectionWorkInProgress) {
        val catalog = repository.refresh(forceConnection = true).getOrThrow()
        _notice.value = "Каталог перечитан: найдено ${catalog.controls.size} элементов. Новые устройства не создавались"
    }

    fun assignTile(slot: Int, controlId: String) = launchWork(null) {
        repository.assignTile(slot, controlId).getOrThrow()
        _tileAddRequests.emit(TileAddRequest(slot, controlId))
    }

    fun clearTile(slot: Int) = launchWork("Плитка $slot удалена из приложения и списка Android") {
        repository.clearTile(slot).getOrThrow()
    }

    fun clearAllTiles() = launchWork("Все плитки удалены из приложения и списка Android") {
        repository.clearAllTiles().getOrThrow()
    }

    fun addPanelItem(controlId: String) = launchWork(null) {
        repository.addPanelItem(controlId).getOrThrow()
        _panelAddRequests.emit(controlId)
        _notice.value = "Добавлено в панель устройств"
    }

    fun removePanelItem(controlId: String) = launchWork("Удалено из панели устройств") {
        repository.removePanelItem(controlId).getOrThrow()
    }

    fun setPanelItemSize(controlId: String, size: PanelItemSize) = launchWork(null) {
        repository.setPanelItemSize(controlId, size).getOrThrow()
    }

    fun setPanelItemAttributes(controlId: String, attributeControlIds: List<String>?) = launchWork(null) {
        repository.setPanelItemAttributes(controlId, attributeControlIds).getOrThrow()
    }

    fun setServicePresentation(
        cardId: String,
        headlineValueKey: String?,
        secondaryValueKeys: List<String>?,
    ) = launchWork("Показатели сервиса сохранены") {
        repository.setServicePresentation(cardId, headlineValueKey, secondaryValueKeys).getOrThrow()
    }

    fun movePanelItem(controlId: String, offset: Int) = launchWork(null) {
        repository.movePanelItem(controlId, offset).getOrThrow()
    }

    fun clearPanelItems() = launchWork("Крупная панель очищена") {
        repository.clearPanelItems().getOrThrow()
    }

    fun requestHealthPermissions() = health.requestPermissions()

    fun onHealthPermissionsChanged() = launchWork("Разрешения Health Connect обновлены") {
        health.onPermissionsChanged()
    }

    fun saveHealthMetrics(metrics: Set<HealthMetric>) = launchWork("Показатели сохранены") {
        health.saveSelectedMetrics(metrics)
    }

    fun createHealthDevice(roomId: String) = launchWork("Виртуальное устройство здоровья готово") {
        health.createDevice(roomId).getOrThrow()
    }

    fun syncHealth() = launchWork("Данные здоровья отправлены в локальный SprutHub") {
        health.syncNow().getOrThrow()
    }

    fun recreateHealthDevice(metrics: Set<HealthMetric>? = null) = launchWork("Состав устройства здоровья обновлён") {
        health.recreateDevice(metrics).getOrThrow()
    }

    fun revokeAllHealthPermissions() = launchWork("Доступ Health Connect отозван") {
        health.revokeAllPermissions()
    }

    fun setHealthEnabled(enabled: Boolean) = launchWork(if (enabled) "Фоновая синхронизация включена" else "Фоновая синхронизация выключена") {
        health.setEnabled(enabled)
    }

    fun resumeManualHealthAccess() = launchWork("Ручная синхронизация здоровья снова доступна") {
        health.resumeManualAccess()
    }

    fun savePhoneSensors(sensors: Set<PhoneSensor>) = launchWork("Показатели телефона сохранены") {
        phone.saveSelectedSensors(sensors)
    }

    fun createPhoneDevice(roomId: String) = launchWork("Виртуальное устройство телефона готово") {
        phone.createDevice(roomId).getOrThrow()
    }

    fun syncPhone() = launchWork("Данные телефона отправлены в SprutHub") {
        phone.syncNow().getOrThrow()
    }

    fun recreatePhoneDevice(sensors: Set<PhoneSensor>? = null) = launchWork("Состав устройства телефона обновлён") {
        phone.recreateDevice(sensors).getOrThrow()
    }

    fun setPhoneEnabled(enabled: Boolean) = launchWork(
        if (enabled) "Фоновая синхронизация телефона включена" else "Фоновая синхронизация телефона выключена",
    ) {
        phone.setEnabled(enabled)
    }

    fun setPhoneSyncMode(mode: PhoneSyncMode) = launchWork(null) {
        phone.setSyncMode(mode)
    }

    fun setPhonePollInterval(interval: PhonePollInterval) = launchWork(null) {
        phone.setPollInterval(interval)
    }

    fun setPhoneWatchdogEnabled(enabled: Boolean) = launchWork(null) {
        phone.setWatchdogEnabled(enabled)
    }

    fun checkAndRepairPhoneReliability() = launchWork(null) {
        val report = phone.checkAndRepairReliability().getOrThrow()
        _notice.value = report.message
    }

    fun refreshPhoneStatus() = phone.refreshRuntimeStatus()

    fun requestCurrentCoordinates() = launchWork(null) {
        _coordinateResults.emit(presence.currentCoordinates().getOrThrow())
        _notice.value = "Текущая точка определена"
    }

    fun addPresenceZone(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Double,
        roomId: String,
        publishDistance: Boolean,
    ) = launchWork("Зона присутствия создана") {
        presence.addZone(name, latitude, longitude, radiusMeters, roomId, publishDistance).getOrThrow()
    }

    fun setPresenceZoneEnabled(id: String, enabled: Boolean) = launchWork(null) {
        presence.setEnabled(id, enabled).getOrThrow()
    }

    fun removePresenceZone(id: String) = launchWork("Зона удалена") {
        presence.removeZone(id).getOrThrow()
    }

    fun syncPresenceZones() = launchWork("Зоны синхронизированы") {
        presence.syncNow().getOrThrow()
    }

    fun onLocationPermissionsChanged() = presence.refreshPermissionState()

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun restartOnboarding() {
        _onboardingRequired.value = true
    }

    fun completeOnboarding() = launchWork(null) {
        settings.markOnboardingComplete()
        _onboardingRequired.value = false
    }

    private fun launchWork(
        successMessage: String?,
        exclusiveGuard: AtomicBoolean? = null,
        block: suspend () -> Unit,
    ) {
        if (exclusiveGuard != null && !exclusiveGuard.compareAndSet(false, true)) {
            _notice.value = "Проверка подключения уже выполняется"
            return
        }
        viewModelScope.launch {
            activeWorkCount.incrementAndGet()
            _busy.value = true
            try {
                block()
                if (successMessage != null) _notice.value = successMessage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _notice.value = error.message ?: "Операция не выполнена"
            } finally {
                exclusiveGuard?.set(false)
                _busy.value = activeWorkCount.decrementAndGet() > 0
            }
        }
    }
}

internal data class ConnectionSettingsCandidate(
    val config: HubConfig,
    val passwordUpdate: HubPasswordUpdate,
)

internal fun buildConnectionSettingsCandidate(
    stored: HubConfig,
    mode: ConnectionMode,
    localUrl: String,
    cloudUrl: String,
    serial: String,
    email: String,
    newLocalPassword: String,
    newCloudPassword: String,
): ConnectionSettingsCandidate {
    val passwordUpdate = HubPasswordUpdate(
        localPassword = newLocalPassword.takeIf(String::isNotEmpty),
        cloudPassword = newCloudPassword.takeIf(String::isNotEmpty),
    )
    return ConnectionSettingsCandidate(
        config = normalizeAndValidateHubConfig(
            HubConfig(
                mode = mode,
                localUrl = localUrl,
                cloudUrl = cloudUrl,
                serial = serial,
                email = email,
                localPassword = passwordUpdate.localPassword ?: stored.localPassword,
                cloudPassword = passwordUpdate.cloudPassword ?: stored.cloudPassword,
            ),
        ),
        passwordUpdate = passwordUpdate,
    )
}

data class TileAddRequest(val slot: Int, val controlId: String)

data class MainUiState(
    val config: HubConfig = HubConfig(),
    val catalog: SprutCatalog = SprutCatalog(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val assignments: List<TileAssignment> = emptyList(),
    val panelItems: List<PanelItem> = emptyList(),
    val servicePresentations: List<ServicePresentationPreference> = emptyList(),
    val diagnostics: List<DiagnosticEvent> = emptyList(),
)
