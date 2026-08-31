package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState

internal enum class SettingsSection(
    val title: String,
    val description: String,
) {
    CONNECTION(
        title = "Подключение",
        description = "Адреса SprutHub, учётная запись и проверка связи",
    ),
    HEALTH(
        title = "Здоровье",
        description = "Health Connect, показатели и виртуальное устройство",
    ),
    PHONE(
        title = "Телефон",
        description = "Датчики телефона, частота и фоновая работа",
    ),
    PRESENCE(
        title = "Зоны и присутствие",
        description = "Дом, другие зоны, радиус и фоновая геопозиция",
    ),
    DIAGNOSTICS(
        title = "Надёжность и диагностика",
        description = "Разрешения, состояние фона, журнал и экспорт",
    ),
}

internal enum class SetupTone {
    READY,
    ATTENTION,
    OPTIONAL,
}

internal data class SetupOverviewItem(
    val section: SettingsSection,
    val status: String,
    val detail: String,
    val tone: SetupTone,
)

internal fun buildSetupOverview(
    ui: MainUiState,
    health: HealthUiState,
    phone: PhoneUiState,
    presence: PresenceUiState,
): List<SetupOverviewItem> = listOf(
    connectionOverview(ui),
    healthOverview(health),
    phoneOverview(phone),
    presenceOverview(presence),
)

private fun connectionOverview(ui: MainUiState): SetupOverviewItem {
    val connected = ui.connection.phase == ConnectionPhase.CONNECTED_LOCAL ||
        ui.connection.phase == ConnectionPhase.CONNECTED_CLOUD
    return SetupOverviewItem(
        section = SettingsSection.CONNECTION,
        status = when (ui.connection.phase) {
            ConnectionPhase.CONNECTED_LOCAL -> "Подключено дома"
            ConnectionPhase.CONNECTED_CLOUD -> "Подключено через облако"
            ConnectionPhase.CONNECTING -> "Проверяем…"
            ConnectionPhase.ERROR -> "Нужно исправить"
            ConnectionPhase.IDLE -> "Нужно проверить"
        },
        detail = ui.connection.message,
        tone = if (connected) SetupTone.READY else SetupTone.ATTENTION,
    )
}

private fun healthOverview(health: HealthUiState): SetupOverviewItem = when {
    !health.available -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Недоступно",
        "Health Connect не найден на этом телефоне",
        SetupTone.OPTIONAL,
    )
    health.binding == null -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Не подключено",
        "Необязательно — настройте, если хотите передавать показатели здоровья",
        SetupTone.OPTIONAL,
    )
    !health.allSelectedPermissionsGranted ||
        (health.backgroundReadAvailable && !health.backgroundReadGranted) -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Нужны разрешения",
        health.message,
        SetupTone.ATTENTION,
    )
    health.enabled -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Работает в фоне",
        health.message,
        SetupTone.READY,
    )
    else -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Фон выключен",
        "Устройство создано, но автоматическая синхронизация выключена",
        SetupTone.ATTENTION,
    )
}

private fun phoneOverview(phone: PhoneUiState): SetupOverviewItem = when {
    phone.binding == null -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Не подключено",
        "Необязательно — заряд, сеть и другие датчики можно добавить позже",
        SetupTone.OPTIONAL,
    )
    !phone.syncSettings.enabled -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Фон выключен",
        "Устройство создано, но автоматическая синхронизация выключена",
        SetupTone.ATTENTION,
    )
    phone.syncSettings.mode == PhoneSyncMode.LIVE && !phone.monitorRunning -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Нужно проверить фон",
        "Постоянный режим выбран, но монитор сейчас не работает",
        SetupTone.ATTENTION,
    )
    else -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Синхронизация включена",
        phone.message,
        SetupTone.READY,
    )
}

private fun presenceOverview(presence: PresenceUiState): SetupOverviewItem = when {
    presence.zones.isEmpty() -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Не настроено",
        "Необязательно — добавьте дом или другую зону, если это нужно",
        SetupTone.OPTIONAL,
    )
    !presence.permissions.preciseGranted || !presence.permissions.backgroundGranted -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Нужны разрешения",
        "Для надёжного входа и выхода нужна точная геопозиция в фоне",
        SetupTone.ATTENTION,
    )
    !presence.geofencesRegistered -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Нужно проверить",
        presence.message,
        SetupTone.ATTENTION,
    )
    else -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Работает",
        "Активных зон: ${presence.zones.count { it.enabled }}",
        SetupTone.READY,
    )
}
