package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.PhoneSyncMode
import io.github.nikitau.spruthubhelper.health.HealthUiState
import io.github.nikitau.spruthubhelper.phone.PhoneUiState
import io.github.nikitau.spruthubhelper.presence.PresenceUiState
import io.github.nikitau.spruthubhelper.presence.PresenceZone

internal enum class GuidanceAction {
    SAVE_AND_TEST_CONNECTION,
    REFRESH_CATALOG,
    OPEN_HEALTH_CONNECT,
    REQUEST_HEALTH_PERMISSIONS,
    CREATE_HEALTH_DEVICE,
    RECREATE_HEALTH_DEVICE,
    ENABLE_HEALTH_BACKGROUND,
    SYNC_HEALTH,
    CREATE_PHONE_DEVICE,
    RECREATE_PHONE_DEVICE,
    ENABLE_PHONE_BACKGROUND,
    REQUEST_PHONE_LIVE_MODE,
    REQUEST_PHONE_WATCHDOG,
    OPEN_BATTERY_SETTINGS,
    SYNC_PHONE,
    REQUEST_FOREGROUND_LOCATION,
    OPEN_BACKGROUND_LOCATION_SETTINGS,
    FILL_CURRENT_LOCATION,
    SYNC_PRESENCE,
}

internal data class SectionGuidance(
    val progress: String,
    val title: String,
    val detail: String,
    val tone: SetupTone,
    val action: GuidanceAction? = null,
    val actionLabel: String? = null,
)

internal fun connectionGuidance(
    ui: MainUiState,
    hasUnsavedChanges: Boolean = false,
): SectionGuidance = if (hasUnsavedChanges) {
    SectionGuidance(
        progress = "Есть несохранённые изменения",
        title = "Проверьте новое подключение",
        detail = "Сохраните адреса и учётные данные, затем приложение сразу проверит доступ и перечитает каталог.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.SAVE_AND_TEST_CONNECTION,
        actionLabel = "Сохранить и проверить",
    )
} else when (ui.connection.phase) {
    ConnectionPhase.CONNECTED_LOCAL,
    ConnectionPhase.CONNECTED_CLOUD,
    -> SectionGuidance(
        progress = "Подключение готово",
        title = "SprutHub отвечает",
        detail = "Можно управлять устройствами. Перечитайте каталог, если в SprutHub что-то изменилось.",
        tone = SetupTone.READY,
        action = GuidanceAction.REFRESH_CATALOG,
        actionLabel = "Перечитать устройства",
    )
    ConnectionPhase.CONNECTING -> SectionGuidance(
        progress = "Проверка подключения",
        title = "Подождите немного",
        detail = "Приложение входит в SprutHub и загружает комнаты, устройства и сценарии.",
        tone = SetupTone.OPTIONAL,
    )
    ConnectionPhase.IDLE,
    ConnectionPhase.ERROR,
    -> SectionGuidance(
        progress = "Шаг 1 из 1",
        title = "Проверьте доступ к SprutHub",
        detail = "Заполните адреса и учётные данные ниже, затем сохраните и проверьте подключение.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.SAVE_AND_TEST_CONNECTION,
        actionLabel = "Сохранить и проверить",
    )
}

internal fun healthGuidance(
    health: HealthUiState,
    configurationMatches: Boolean = health.configurationMatches,
): SectionGuidance = when {
    !health.available -> SectionGuidance(
        progress = "Health Connect недоступен",
        title = "Проверьте Health Connect",
        detail = "Установите или обновите Health Connect и убедитесь, что он поддерживается телефоном.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.OPEN_HEALTH_CONNECT,
        actionLabel = "Открыть Health Connect",
    )
    !health.allSelectedPermissionsGranted ||
        (health.backgroundReadAvailable && !health.backgroundReadGranted) -> SectionGuidance(
        progress = "Шаг 1 из 3",
        title = "Разрешите выбранные показатели",
        detail = "Android покажет только те категории Health Connect, которые отмечены ниже.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.REQUEST_HEALTH_PERMISSIONS,
        actionLabel = "Выдать разрешения",
    )
    health.binding == null -> SectionGuidance(
        progress = "Шаг 2 из 3",
        title = "Создайте устройство здоровья",
        detail = "Проверьте показатели и комнату ниже. Устройство создаётся только после этого нажатия.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.CREATE_HEALTH_DEVICE,
        actionLabel = "Создать в SprutHub",
    )
    !configurationMatches -> SectionGuidance(
        progress = "Нужно применить изменения",
        title = "Состав показателей изменён",
        detail = "Чтобы снятые показатели исчезли, приложение безопасно заменит только созданный им аксессуар здоровья.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.RECREATE_HEALTH_DEVICE,
        actionLabel = "Применить новый состав",
    )
    health.backgroundReadAvailable && !health.enabled -> SectionGuidance(
        progress = "Шаг 3 из 3",
        title = "Включите фоновую синхронизацию",
        detail = "Health Connect будет проверяться системной задачей примерно раз в 15 минут.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.ENABLE_HEALTH_BACKGROUND,
        actionLabel = "Включить фон",
    )
    else -> SectionGuidance(
        progress = "Здоровье настроено",
        title = if (health.backgroundReadAvailable) "Фоновая синхронизация работает" else "Доступна ручная синхронизация",
        detail = if (health.backgroundReadAvailable) {
            "Android может немного сдвигать 15-минутный интервал. Ручная проверка доступна в любой момент."
        } else {
            "Эта версия Health Connect не предоставляет приложению фоновое чтение."
        },
        tone = SetupTone.READY,
        action = GuidanceAction.SYNC_HEALTH,
        actionLabel = "Синхронизировать сейчас",
    )
}

internal fun phoneGuidance(
    phone: PhoneUiState,
    configurationMatches: Boolean = phone.configurationMatches,
): SectionGuidance = when {
    phone.binding == null -> SectionGuidance(
        progress = "Шаг 1 из 2",
        title = "Создайте устройство телефона",
        detail = "Выберите датчики и комнату ниже. До нажатия устройство в SprutHub не создаётся.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.CREATE_PHONE_DEVICE,
        actionLabel = "Создать в SprutHub",
    )
    !configurationMatches -> SectionGuidance(
        progress = "Нужно применить изменения",
        title = "Состав датчиков изменён",
        detail = "Приложение заменит только созданный им аксессуар телефона и сохранит выбранную комнату.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.RECREATE_PHONE_DEVICE,
        actionLabel = "Применить новый состав",
    )
    !phone.syncSettings.enabled -> SectionGuidance(
        progress = "Шаг 2 из 2",
        title = "Включите фоновую синхронизацию",
        detail = "События и страховочный опрос начнут отправлять выбранные показатели автоматически.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.ENABLE_PHONE_BACKGROUND,
        actionLabel = "Включить фон",
    )
    phone.syncSettings.mode == PhoneSyncMode.LIVE && !phone.notificationPermissionGranted -> SectionGuidance(
        progress = "Нужно разрешение Android",
        title = "Разрешите уведомления",
        detail = "Они нужны для постоянного режима и локального предупреждения о застывшей синхронизации.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.REQUEST_PHONE_LIVE_MODE,
        actionLabel = "Разрешить и запустить",
    )
    phone.syncSettings.watchdogEnabled && !phone.notificationPermissionGranted -> SectionGuidance(
        progress = "Нужно разрешение Android",
        title = "Разрешите предупреждения",
        detail = "Без уведомлений локальный watchdog не сможет сообщить, что синхронизация давно не проходила.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.REQUEST_PHONE_WATCHDOG,
        actionLabel = "Разрешить уведомления",
    )
    !phone.batteryOptimizationIgnored -> SectionGuidance(
        progress = "Рекомендуется для надёжности",
        title = "Снимите ограничение батареи",
        detail = "Иначе прошивка может задерживать события и 15-минутные контрольные запуски.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.OPEN_BATTERY_SETTINGS,
        actionLabel = "Открыть настройки батареи",
    )
    phone.syncSettings.mode == PhoneSyncMode.LIVE && !phone.monitorRunning -> SectionGuidance(
        progress = "Постоянный режим остановлен",
        title = "Перезапустите монитор телефона",
        detail = "Разрешения сохранены; приложение попробует снова запустить событийную синхронизацию.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.REQUEST_PHONE_LIVE_MODE,
        actionLabel = "Запустить снова",
    )
    else -> SectionGuidance(
        progress = "Телефон настроен",
        title = if (phone.syncSettings.mode == PhoneSyncMode.LIVE) "Событийная синхронизация работает" else "Периодическая синхронизация работает",
        detail = if (phone.syncSettings.mode == PhoneSyncMode.LIVE) {
            "Зарядка, сеть, экран и энергосбережение отправляются по событию; опрос остаётся страховкой."
        } else {
            "Android запускает контрольную задачу примерно раз в 15 минут."
        },
        tone = SetupTone.READY,
        action = GuidanceAction.SYNC_PHONE,
        actionLabel = "Синхронизировать сейчас",
    )
}

internal fun presenceGuidance(presence: PresenceUiState): SectionGuidance = when {
    !presence.permissions.preciseGranted -> SectionGuidance(
        progress = "Шаг 1 из 3",
        title = "Разрешите точную геопозицию",
        detail = "Она нужна для радиуса зоны и расчёта расстояния до выбранной точки.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.REQUEST_FOREGROUND_LOCATION,
        actionLabel = "Разрешить геопозицию",
    )
    !presence.permissions.backgroundGranted -> SectionGuidance(
        progress = "Шаг 2 из 3",
        title = "Разрешите геопозицию «Всегда»",
        detail = "Android выдаёт этот уровень доступа только на системной странице приложения.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.OPEN_BACKGROUND_LOCATION_SETTINGS,
        actionLabel = "Открыть разрешения Android",
    )
    presence.zones.isEmpty() -> SectionGuidance(
        progress = "Шаг 3 из 3",
        title = "Добавьте первую зону",
        detail = "Подставьте текущую точку, затем проверьте название, радиус и комнату ниже.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.FILL_CURRENT_LOCATION,
        actionLabel = "Подставить текущую точку",
    )
    presence.zones.none(PresenceZone::enabled) -> SectionGuidance(
        progress = "Присутствие приостановлено",
        title = "Все зоны выключены",
        detail = "Это не ошибка. Включите нужную зону ниже, когда снова понадобится отслеживание.",
        tone = SetupTone.OPTIONAL,
    )
    !presence.geofencesRegistered -> SectionGuidance(
        progress = "Зоны требуют внимания",
        title = "Перерегистрируйте геозоны",
        detail = "Разрешения есть, но Android сейчас не подтвердил активное наблюдение за границами.",
        tone = SetupTone.ATTENTION,
        action = GuidanceAction.SYNC_PRESENCE,
        actionLabel = "Восстановить и проверить",
    )
    else -> SectionGuidance(
        progress = "Присутствие настроено",
        title = "Android следит за границами зон",
        detail = "Вход и выход приходят системным событием; расстояние дополнительно обновляется периодически.",
        tone = SetupTone.READY,
        action = GuidanceAction.SYNC_PRESENCE,
        actionLabel = "Обновить зоны сейчас",
    )
}
