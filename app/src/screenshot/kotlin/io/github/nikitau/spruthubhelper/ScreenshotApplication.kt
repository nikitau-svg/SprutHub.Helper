package io.github.nikitau.spruthubhelper

import android.app.Application
import io.github.nikitau.spruthubhelper.data.CatalogCache
import io.github.nikitau.spruthubhelper.data.ConnectionPhase
import io.github.nikitau.spruthubhelper.data.ConnectionStatus
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutRoom
import io.github.nikitau.spruthubhelper.data.SprutValue
import io.github.nikitau.spruthubhelper.data.SprutValueOption
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.health.HealthReading
import kotlinx.coroutines.runBlocking

/**
 * Isolated, synthetic state used only by the local `screenshot` build type.
 *
 * Its application id has a `.screenshots` suffix, so it neither reads nor
 * overwrites the installed production app. No endpoint, account, hub id,
 * health value or coordinate from the owner is used here.
 */
class ScreenshotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val catalog = screenshotCatalog()
        runBlocking {
            CatalogCache(this@ScreenshotApplication).write(catalog)
            SettingsRepository(this@ScreenshotApplication).apply {
                markOnboardingComplete()
                savePhoneSensors(ScreenshotPhoneReadings.keys)
                clearAllTiles()
                clearPanelItems()
                val cards = buildServiceControlCards(catalog.controls)
                cards.forEach { addPanelItem(it.id) }
                catalog.controls
                    .filter { it.id in setOf("climate-power", "evening-scene", "air-quality") }
                    .forEachIndexed { index, control -> assignTile(index + 1, control.id) }
            }
        }
        AppGraph.initialize(
            context = this,
            backgroundRuntimeEnabled = false,
            remoteOperationsEnabled = false,
            phoneReadingsOverride = ScreenshotPhoneReadings,
        )
        AppGraph.repository.installScreenshotState(
            catalog = catalog,
            connection = ConnectionStatus(
                phase = ConnectionPhase.CONNECTED_LOCAL,
                message = "Подключено дома · каталог обновлён только что",
                lastSuccessEpochMs = catalog.refreshedAtEpochMs,
            ),
        )
    }
}

private val ScreenshotPhoneReadings = linkedMapOf(
    PhoneSensor.BATTERY_LEVEL to HealthReading(numberValue = 82.0),
    PhoneSensor.IS_CHARGING to HealthReading(boolValue = true),
    PhoneSensor.CHARGER_TYPE to HealthReading(stringValue = "Беспроводная"),
    PhoneSensor.CONNECTION_TYPE to HealthReading(stringValue = "Wi‑Fi"),
    PhoneSensor.NETWORK_VALIDATED to HealthReading(boolValue = true),
    PhoneSensor.DEVICE_MODEL to HealthReading(stringValue = "Демо-телефон"),
    PhoneSensor.ANDROID_VERSION to HealthReading(stringValue = "Android 16 · демо"),
    PhoneSensor.SCREEN_INTERACTIVE to HealthReading(boolValue = true),
    PhoneSensor.SYNC_HEARTBEAT to HealthReading(numberValue = 123_456.0),
    PhoneSensor.LAST_SYNC to HealthReading(stringValue = "Только что"),
)

private fun screenshotCatalog(): SprutCatalog {
    val now = System.currentTimeMillis()
    return SprutCatalog(
        refreshedAtEpochMs = now,
        hubVersion = "2.0-demo",
        rooms = listOf(
            SprutRoom("living", "Гостиная"),
            SprutRoom("bedroom", "Спальня"),
            SprutRoom("hall", "Прихожая"),
        ),
        controls = listOf(
            control(
                id = "climate-power",
                accessory = "climate",
                service = "thermostat",
                characteristic = "active",
                title = "Климат гостиной",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                behavior = ControlBehavior.TOGGLE_RANGE,
                value = SprutValue(boolValue = true, numberValue = 22.0),
                characteristicType = "Active",
                characteristicName = "Питание",
                rangeCharacteristic = "target-temperature",
                rangeCharacteristicType = "TargetTemperature",
                minimum = 16.0,
                maximum = 30.0,
                unit = "celsius",
                primary = true,
            ),
            sensor(
                id = "climate-current",
                accessory = "climate",
                service = "thermostat",
                characteristic = "current-temperature",
                title = "Климат гостиной",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                value = SprutValue(numberValue = 23.4),
                characteristicType = "CurrentTemperature",
                characteristicName = "Сейчас",
                unit = "celsius",
            ),
            sensor(
                id = "climate-humidity",
                accessory = "climate",
                service = "thermostat",
                characteristic = "humidity",
                title = "Климат гостиной",
                serviceName = "Кондиционер",
                room = "Гостиная",
                kind = DeviceKind.THERMOSTAT,
                value = SprutValue(numberValue = 46.0),
                characteristicType = "CurrentRelativeHumidity",
                characteristicName = "Влажность",
                unit = "percentage",
            ),
            control(
                id = "floor-lamp",
                accessory = "lighting",
                service = "floor-lamp",
                characteristic = "on",
                title = "Торшер",
                serviceName = "Свет",
                room = "Гостиная",
                kind = DeviceKind.LIGHT,
                behavior = ControlBehavior.TOGGLE_RANGE,
                value = SprutValue(boolValue = true, numberValue = 64.0),
                characteristicType = "On",
                characteristicName = "Состояние",
                rangeCharacteristic = "brightness",
                rangeCharacteristicType = "Brightness",
                minimum = 1.0,
                maximum = 100.0,
                unit = "percentage",
                primary = true,
            ),
            control(
                id = "curtain-position",
                accessory = "curtains",
                service = "window-covering",
                characteristic = "target-position",
                title = "Шторы",
                serviceName = "Шторы",
                room = "Гостиная",
                kind = DeviceKind.CURTAIN,
                behavior = ControlBehavior.RANGE,
                value = SprutValue(numberValue = 68.0),
                characteristicType = "TargetPosition",
                characteristicName = "Положение",
                minimum = 0.0,
                maximum = 100.0,
                unit = "percentage",
                primary = true,
            ),
            sensor(
                id = "curtain-current",
                accessory = "curtains",
                service = "window-covering",
                characteristic = "current-position",
                title = "Шторы",
                serviceName = "Шторы",
                room = "Гостиная",
                kind = DeviceKind.CURTAIN,
                value = SprutValue(numberValue = 68.0),
                characteristicType = "CurrentPosition",
                characteristicName = "Открыто",
                unit = "percentage",
            ),
            sensor(
                id = "air-quality",
                accessory = "air-monitor",
                service = "air-quality",
                characteristic = "quality",
                title = "Воздух",
                serviceName = "Качество воздуха",
                room = "Спальня",
                kind = DeviceKind.SENSOR,
                value = SprutValue(stringValue = "Отличное"),
                characteristicType = "AirQuality",
                characteristicName = "Качество воздуха",
                primary = true,
            ),
            sensor(
                id = "air-pm25",
                accessory = "air-monitor",
                service = "air-quality",
                characteristic = "pm25",
                title = "Воздух",
                serviceName = "Качество воздуха",
                room = "Спальня",
                kind = DeviceKind.SENSOR,
                value = SprutValue(numberValue = 7.0),
                characteristicType = "PM2.5Density",
                characteristicName = "PM2.5",
                unit = "@unit_ug_m3",
            ),
            sensor(
                id = "air-co2",
                accessory = "air-monitor",
                service = "air-quality",
                characteristic = "co2",
                title = "Воздух",
                serviceName = "Качество воздуха",
                room = "Спальня",
                kind = DeviceKind.SENSOR,
                value = SprutValue(numberValue = 438.0),
                characteristicType = "CarbonDioxideLevel",
                characteristicName = "CO₂",
                unit = "ppm",
            ),
            control(
                id = "coffee-outlet",
                accessory = "coffee",
                service = "outlet",
                characteristic = "on",
                title = "Кофейная розетка",
                serviceName = "Розетка",
                room = "Гостиная",
                kind = DeviceKind.OUTLET,
                behavior = ControlBehavior.TOGGLE,
                value = SprutValue(boolValue = false),
                characteristicType = "On",
                characteristicName = "Состояние",
                primary = true,
            ),
            sensor(
                id = "coffee-power",
                accessory = "coffee",
                service = "outlet",
                characteristic = "power",
                title = "Кофейная розетка",
                serviceName = "Розетка",
                room = "Гостиная",
                kind = DeviceKind.OUTLET,
                value = SprutValue(numberValue = 0.0),
                characteristicType = "C_Power",
                characteristicName = "Мощность",
                unit = "w",
            ),
            control(
                id = "vacuum-mode",
                accessory = "vacuum",
                service = "vacuum",
                characteristic = "target-state",
                title = "Робот-пылесос",
                serviceName = "Уборка",
                room = "Прихожая",
                kind = DeviceKind.VACUUM,
                behavior = ControlBehavior.OPTIONS,
                value = SprutValue(numberValue = 1.0),
                characteristicType = "TargetOperationalState",
                characteristicName = "Режим",
                primary = true,
                options = listOf(
                    SprutValueOption(SprutValue(numberValue = 0.0), "idle", "На базе"),
                    SprutValueOption(SprutValue(numberValue = 1.0), "cleaning", "Убирает"),
                    SprutValueOption(SprutValue(numberValue = 2.0), "paused", "Пауза"),
                ),
                valueField = "intValue",
            ),
            control(
                id = "evening-scene",
                accessory = "scenes",
                service = "evening",
                characteristic = "scene-evening",
                title = "Уютный вечер",
                serviceName = "Сценарий",
                room = "Гостиная",
                kind = DeviceKind.SCENE,
                behavior = ControlBehavior.BUTTON,
                value = SprutValue(boolValue = false),
                characteristicType = "Scenario",
                characteristicName = "Запуск",
                primary = true,
            ),
        ),
    )
}

private fun control(
    id: String,
    accessory: String,
    service: String,
    characteristic: String,
    title: String,
    serviceName: String,
    room: String,
    kind: DeviceKind,
    behavior: ControlBehavior,
    value: SprutValue,
    characteristicType: String,
    characteristicName: String,
    rangeCharacteristic: String? = null,
    rangeCharacteristicType: String = "",
    minimum: Double = 0.0,
    maximum: Double = 100.0,
    unit: String = "",
    primary: Boolean = false,
    options: List<SprutValueOption> = emptyList(),
    valueField: String = "boolValue",
): SprutControl = SprutControl(
    id = id,
    accessoryId = accessory,
    serviceId = service,
    characteristicId = characteristic,
    rangeCharacteristicId = rangeCharacteristic,
    title = title,
    subtitle = serviceName,
    room = room,
    kind = kind,
    behavior = behavior,
    value = value,
    minimum = minimum,
    maximum = maximum,
    unit = unit,
    writable = behavior != ControlBehavior.SENSOR,
    sourceType = serviceType(kind),
    serviceName = serviceName,
    characteristicType = characteristicType,
    characteristicName = characteristicName,
    rangeCharacteristicType = rangeCharacteristicType,
    servicePrimary = primary,
    valueOptions = options,
    valueField = valueField,
    rangeValueField = "doubleValue",
)

private fun sensor(
    id: String,
    accessory: String,
    service: String,
    characteristic: String,
    title: String,
    serviceName: String,
    room: String,
    kind: DeviceKind,
    value: SprutValue,
    characteristicType: String,
    characteristicName: String,
    unit: String = "",
    primary: Boolean = false,
): SprutControl = control(
    id = id,
    accessory = accessory,
    service = service,
    characteristic = characteristic,
    title = title,
    serviceName = serviceName,
    room = room,
    kind = kind,
    behavior = ControlBehavior.SENSOR,
    value = value,
    characteristicType = characteristicType,
    characteristicName = characteristicName,
    unit = unit,
    primary = primary,
    valueField = when {
        value.stringValue != null -> "stringValue"
        value.numberValue != null -> "doubleValue"
        else -> "boolValue"
    },
)

private fun serviceType(kind: DeviceKind): String = when (kind) {
    DeviceKind.LIGHT -> "Lightbulb"
    DeviceKind.OUTLET -> "Outlet"
    DeviceKind.CURTAIN -> "WindowCovering"
    DeviceKind.THERMOSTAT -> "Thermostat"
    DeviceKind.VACUUM -> "C_VacuumCleaner"
    DeviceKind.SCENE -> "Scenario"
    DeviceKind.SENSOR -> "AirQualitySensor"
    else -> kind.name
}
