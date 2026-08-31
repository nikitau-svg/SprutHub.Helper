package io.github.nikitau.spruthubhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ConnectionMode
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
import io.github.nikitau.spruthubhelper.data.TileAssignment
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    private val settings = AppGraph.settings
    private val repository = AppGraph.repository
    private val health = AppGraph.health
    private val phone = AppGraph.phone
    private val presence = AppGraph.presence
    private val _busy = MutableStateFlow(false)
    private val _notice = MutableStateFlow<String?>(null)
    private val _tileAddRequests = MutableSharedFlow<TileAddRequest>(extraBufferCapacity = 1)
    private val _panelAddRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val _coordinateResults = MutableSharedFlow<Pair<Double, Double>>(extraBufferCapacity = 1)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val notice: StateFlow<String?> = _notice.asStateFlow()
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
        combine(repository.tileAssignments, repository.panelItems) { tiles, panel -> tiles to panel },
        repository.diagnostics,
    ) { config, catalog, connection, androidItems, diagnostics ->
        MainUiState(config, catalog, connection, androidItems.first, androidItems.second, diagnostics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        phone.ensureLiveMonitor()
        viewModelScope.launch {
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

    fun saveSettings(
        mode: ConnectionMode,
        localUrl: String,
        cloudUrl: String,
        serial: String,
        email: String,
        newLocalPassword: String,
        newCloudPassword: String,
    ) = launchWork("Настройки сохранены") {
        saveSettingsNow(
            mode,
            localUrl,
            cloudUrl,
            serial,
            email,
            newLocalPassword,
            newCloudPassword,
        )
    }

    fun saveAndTestSettings(
        mode: ConnectionMode,
        localUrl: String,
        cloudUrl: String,
        serial: String,
        email: String,
        newLocalPassword: String,
        newCloudPassword: String,
    ) = launchWork(null) {
        saveSettingsNow(
            mode,
            localUrl,
            cloudUrl,
            serial,
            email,
            newLocalPassword,
            newCloudPassword,
        )
        val catalog = repository.refresh(forceConnection = true).getOrThrow()
        _notice.value = "Каталог перечитан: найдено ${catalog.controls.size} элементов. Новые устройства не создавались"
    }

    fun testConnection() = launchWork(null) {
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
        _notice.value = "Добавлено в крупную панель"
    }

    fun removePanelItem(controlId: String) = launchWork("Удалено из крупной панели") {
        repository.removePanelItem(controlId).getOrThrow()
    }

    fun setPanelItemSize(controlId: String, size: PanelItemSize) = launchWork(null) {
        repository.setPanelItemSize(controlId, size).getOrThrow()
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

    private suspend fun saveSettingsNow(
        mode: ConnectionMode,
        localUrl: String,
        cloudUrl: String,
        serial: String,
        email: String,
        newLocalPassword: String,
        newCloudPassword: String,
    ) {
        settings.saveConfig(
            config = HubConfig(
                mode = mode,
                localUrl = localUrl,
                cloudUrl = cloudUrl,
                serial = serial,
                email = email,
            ),
            passwordUpdate = HubPasswordUpdate(
                localPassword = newLocalPassword.takeIf(String::isNotEmpty),
                cloudPassword = newCloudPassword.takeIf(String::isNotEmpty),
            ),
        )
        repository.reconnectAfterSettingsChange()
    }

    private fun launchWork(successMessage: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }
                .onSuccess { if (successMessage != null) _notice.value = successMessage }
                .onFailure { _notice.value = it.message ?: "Операция не выполнена" }
            _busy.value = false
        }
    }
}

data class TileAddRequest(val slot: Int, val controlId: String)

data class MainUiState(
    val config: HubConfig = HubConfig(),
    val catalog: SprutCatalog = SprutCatalog(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val assignments: List<TileAssignment> = emptyList(),
    val panelItems: List<PanelItem> = emptyList(),
    val diagnostics: List<DiagnosticEvent> = emptyList(),
)
