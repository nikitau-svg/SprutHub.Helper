package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
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

internal data class HomeReadiness(
    val status: String,
    val title: String,
    val detail: String,
    val tone: SetupTone,
    val targetSection: SettingsSection? = null,
    val actionLabel: String? = null,
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

internal fun buildHomeReadiness(
    ui: MainUiState,
    health: HealthUiState,
    phone: PhoneUiState,
    presence: PresenceUiState,
): HomeReadiness {
    val connected = ui.connection.phase == ConnectionPhase.CONNECTED_LOCAL ||
        ui.connection.phase == ConnectionPhase.CONNECTED_CLOUD
    if (ui.connection.phase == ConnectionPhase.CONNECTING) {
        return HomeReadiness(
            status = "Проверяем подключение",
            title = "SprutHub отвечает…",
            detail = "Загружаем комнаты, устройства и сценарии. Ничего дополнительно нажимать не нужно.",
            tone = SetupTone.OPTIONAL,
        )
    }
    if (!connected) {
        return HomeReadiness(
            status = "Нужно одно действие",
            title = "Подключите SprutHub",
            detail = ui.connection.message.takeUnless { it.isBlank() || it == "Не проверено" }
                ?: "Укажите адреса и отдельные локальный и облачный пароли, затем выполните проверку.",
            tone = SetupTone.ATTENTION,
            targetSection = SettingsSection.CONNECTION,
            actionLabel = "Настроить подключение",
        )
    }
    if (ui.catalog.controls.isEmpty()) {
        return HomeReadiness(
            status = "Нужно одно действие",
            title = "Перечитайте устройства",
            detail = "SprutHub подключён, но подтверждённый каталог ещё не загружен.",
            tone = SetupTone.ATTENTION,
            targetSection = SettingsSection.CONNECTION,
            actionLabel = "Проверить каталог",
        )
    }

    val configuredIssues = listOfNotNull(
        phone.binding?.let { SettingsSection.PHONE to phoneGuidance(phone) },
        health.binding?.let { SettingsSection.HEALTH to healthGuidance(health) },
        presence.zones.takeIf { it.isNotEmpty() }?.let {
            SettingsSection.PRESENCE to presenceGuidance(presence)
        },
    )
    configuredIssues.firstOrNull { (_, guidance) -> guidance.tone == SetupTone.ATTENTION }
        ?.let { (section, guidance) ->
            return HomeReadiness(
                status = "${section.title}: нужно действие",
                title = guidance.title,
                detail = guidance.detail,
                tone = SetupTone.ATTENTION,
                targetSection = section,
                actionLabel = "Открыть «${section.title}»",
            )
        }

    val configured = buildList {
        if (phone.binding != null) add("телефон")
        if (health.binding != null) add("здоровье")
        if (presence.zones.isNotEmpty()) add("зоны")
    }
    return HomeReadiness(
        status = "Всё работает",
        title = "SprutHub Helper готов",
        detail = if (configured.isEmpty()) {
            "Каталог актуален. Теперь можно добавлять устройства в крупную панель, виджеты и плитки шторки."
        } else {
            "Каталог актуален; ${configured.joinToString()} не требуют внимания."
        },
        tone = SetupTone.READY,
    )
}

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
        tone = when {
            connected -> SetupTone.READY
            ui.connection.phase == ConnectionPhase.CONNECTING -> SetupTone.OPTIONAL
            else -> SetupTone.ATTENTION
        },
    )
}

private fun healthOverview(health: HealthUiState): SetupOverviewItem = when {
    health.binding == null && !health.available -> SetupOverviewItem(
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
    healthGuidance(health).tone == SetupTone.ATTENTION -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Нужно действие",
        healthGuidance(health).title,
        SetupTone.ATTENTION,
    )
    healthGuidance(health).tone == SetupTone.OPTIONAL -> SetupOverviewItem(
        SettingsSection.HEALTH,
        "Приостановлено",
        healthGuidance(health).title,
        SetupTone.OPTIONAL,
    )
    else -> SetupOverviewItem(
        SettingsSection.HEALTH,
        if (health.enabled) "Работает в фоне" else "Ручная синхронизация",
        health.message,
        SetupTone.READY,
    )
}

private fun phoneOverview(phone: PhoneUiState): SetupOverviewItem = when {
    phone.binding == null -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Не подключено",
        "Необязательно — заряд, сеть и другие датчики можно добавить позже",
        SetupTone.OPTIONAL,
    )
    phoneGuidance(phone).tone == SetupTone.ATTENTION -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Нужно действие",
        phoneGuidance(phone).title,
        SetupTone.ATTENTION,
    )
    phoneGuidance(phone).tone == SetupTone.OPTIONAL -> SetupOverviewItem(
        SettingsSection.PHONE,
        "Приостановлено",
        phoneGuidance(phone).title,
        SetupTone.OPTIONAL,
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
    presenceGuidance(presence).tone == SetupTone.ATTENTION -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Нужно действие",
        presenceGuidance(presence).title,
        SetupTone.ATTENTION,
    )
    presenceGuidance(presence).tone == SetupTone.OPTIONAL -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Приостановлено",
        presenceGuidance(presence).title,
        SetupTone.OPTIONAL,
    )
    else -> SetupOverviewItem(
        SettingsSection.PRESENCE,
        "Работает",
        "Активных зон: ${presence.zones.count { it.enabled }}",
        SetupTone.READY,
    )
}
