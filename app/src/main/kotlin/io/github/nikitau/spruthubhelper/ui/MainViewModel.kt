package io.github.nikitau.spruthubhelper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.nikitau.spruthubhelper.AppGraph
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.DiagnosticEvent
import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.TileAssignment
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
    private val _busy = MutableStateFlow(false)
    private val _notice = MutableStateFlow<String?>(null)

    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val notice: StateFlow<String?> = _notice.asStateFlow()
    val healthState = health.state
    val healthPermissionRequests = health.permissionRequests
    val uiState: StateFlow<MainUiState> = combine(
        settings.config,
        repository.catalog,
        repository.connectionStatus,
        repository.tileAssignments,
        repository.diagnostics,
    ) { config, catalog, connection, assignments, diagnostics ->
        MainUiState(config, catalog, connection, assignments, diagnostics)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun saveSettings(
        mode: ConnectionMode,
        localUrl: String,
        cloudUrl: String,
        serial: String,
        email: String,
        newPassword: String,
    ) = launchWork("Настройки сохранены") {
        val old = settings.currentConfig()
        settings.saveConfig(
            HubConfig(
                mode = mode,
                localUrl = localUrl,
                cloudUrl = cloudUrl,
                serial = serial,
                email = email,
                password = if (newPassword.isBlank()) old.password else newPassword,
            ),
            replacePassword = newPassword.isNotBlank(),
        )
        repository.reconnectAfterSettingsChange()
    }

    fun testConnection() = launchWork(null) {
        val catalog = repository.refresh(forceConnection = true).getOrThrow()
        _notice.value = "Готово: найдено ${catalog.controls.size} элементов"
    }

    fun assignTile(slot: Int, controlId: String) = launchWork("Плитка $slot настроена") {
        repository.assignTile(slot, controlId).getOrThrow()
    }

    fun clearTile(slot: Int) = launchWork("Плитка $slot освобождена") {
        repository.clearTile(slot).getOrThrow()
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

    fun setHealthEnabled(enabled: Boolean) = launchWork(if (enabled) "Фоновая синхронизация включена" else "Фоновая синхронизация выключена") {
        health.setEnabled(enabled)
    }

    fun showNotice(message: String) {
        _notice.value = message
    }

    fun consumeNotice() {
        _notice.value = null
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

data class MainUiState(
    val config: HubConfig = HubConfig(),
    val catalog: SprutCatalog = SprutCatalog(),
    val connection: ConnectionStatus = ConnectionStatus(),
    val assignments: List<TileAssignment> = emptyList(),
    val diagnostics: List<DiagnosticEvent> = emptyList(),
)
