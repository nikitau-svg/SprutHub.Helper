package io.github.nikitau.spruthubhelper.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale
import kotlin.math.abs

@Serializable
enum class ConnectionMode {
    AUTO,
    LOCAL,
    CLOUD,
}

@Serializable
data class HubConfig(
    val mode: ConnectionMode = ConnectionMode.AUTO,
    val localUrl: String = DEFAULT_LOCAL_URL,
    val cloudUrl: String = DEFAULT_CLOUD_URL,
    val serial: String = DEFAULT_SERIAL,
    val email: String = "",
    @Transient
    val localPassword: String = "",
    @Transient
    val cloudPassword: String = "",
    /**
     * Compatibility bridge for the original single-password UI.
     *
     * New callers must use [localPassword] and [cloudPassword]. This field is
     * deliberately transient and is never persisted in DataStore.
     */
    @Transient
    val password: String = "",
) {
    val hasLocalPassword: Boolean
        get() = localPassword.isNotEmpty() || password.isNotEmpty()

    val hasCloudPassword: Boolean
        get() = cloudPassword.isNotEmpty() || password.isNotEmpty()

    internal fun passwordFor(isLocal: Boolean): String = if (isLocal) {
        localPassword.ifEmpty { password }
    } else {
        cloudPassword.ifEmpty { password }
    }

    override fun toString(): String =
        "HubConfig(mode=$mode, localUrl=$localUrl, cloudUrl=$cloudUrl, serial=<redacted>, " +
            "email=<redacted>, localPassword=<redacted>, cloudPassword=<redacted>, password=<redacted>)"

    companion object {
        const val DEFAULT_LOCAL_URL = ""
        const val DEFAULT_CLOUD_URL = "wss://web.spruthub.ru/spruthub"
        const val DEFAULT_SERIAL = ""
    }
}

/**
 * Password update contract. `null` preserves the stored secret, an empty
 * string clears it, and a non-empty string replaces it.
 */
data class HubPasswordUpdate(
    val localPassword: String? = null,
    val cloudPassword: String? = null,
) {
    override fun toString(): String =
        "HubPasswordUpdate(localPassword=<redacted>, cloudPassword=<redacted>)"
}

@Serializable
enum class ControlBehavior {
    TOGGLE,
    RANGE,
    TOGGLE_RANGE,
    OPTIONS,
    BUTTON,
    SENSOR,
}

@Serializable
enum class DeviceKind {
    LIGHT,
    SWITCH,
    OUTLET,
    FAN,
    CURTAIN,
    BLINDS,
    SHUTTER,
    LOCK,
    THERMOSTAT,
    GARAGE,
    VALVE,
    SECURITY,
    VACUUM,
    TELEVISION,
    SCENE,
    SENSOR,
    OTHER,
}

@Serializable
data class SprutValue(
    val boolValue: Boolean? = null,
    val numberValue: Double? = null,
    val stringValue: String? = null,
) {
    fun asBooleanOrNull(): Boolean? = boolValue
        ?: numberValue?.let { it > 0.0 }
        ?: stringValue?.let { raw ->
            when (raw.lowercase()) {
                "true", "1", "on", "active", "enabled" -> true
                "false", "0", "off", "inactive", "disabled" -> false
                else -> null
            }
        }

    fun asBoolean(): Boolean = asBooleanOrNull() ?: false

    fun asDouble(): Double = numberValue
        ?: if (boolValue == true) 1.0 else 0.0
}

@Serializable
data class SprutValueOption(
    val value: SprutValue,
    val key: String = "",
    val name: String = "",
)

@Serializable
data class SprutControl(
    val id: String,
    val accessoryId: String,
    val serviceId: String,
    val characteristicId: String,
    val rangeCharacteristicId: String? = null,
    val title: String,
    val subtitle: String = "",
    val room: String = "Без комнаты",
    val kind: DeviceKind = DeviceKind.OTHER,
    val behavior: ControlBehavior = ControlBehavior.TOGGLE,
    val value: SprutValue = SprutValue(),
    val minimum: Double = 0.0,
    val maximum: Double = 100.0,
    val step: Double = 1.0,
    val unit: String = "",
    val writable: Boolean = true,
    val sourceType: String = "",
    /** Stable SprutHub service metadata used to assemble one logical card. */
    val serviceName: String = "",
    val characteristicType: String = "",
    val characteristicName: String = "",
    val rangeCharacteristicType: String = "",
    val servicePrimary: Boolean = false,
    val linkedServiceIds: List<String> = emptyList(),
    /** Server-provided labels for enum/option values, when available. */
    val valueOptions: List<SprutValueOption> = emptyList(),
    val valueField: String = "boolValue",
    val rangeValueField: String = "doubleValue",
) {
    val displayValue: String
        get() = when (behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE -> if (value.asBoolean()) "Включено" else "Выключено"
            ControlBehavior.RANGE -> buildString {
                append(value.asDouble().formatCompact())
                readableSprutUnit(unit).takeIf(String::isNotBlank)?.let { append(" ").append(it) }
            }
            ControlBehavior.OPTIONS -> valueOptions
                .firstOrNull { option -> option.value.sameValueAs(value) }
                ?.let { option -> option.name.ifBlank { option.key } }
                ?.takeIf(String::isNotBlank)
                ?: value.stringValue
                ?: value.numberValue?.formatCompact()
                ?: "—"
            ControlBehavior.BUTTON -> "Готово к запуску"
            ControlBehavior.SENSOR -> buildString {
                append(
                    value.stringValue
                        ?: value.numberValue?.formatCompact()
                        ?: value.boolValue?.let { if (it) "Да" else "Нет" }
                        ?: "—",
                )
                if (value.numberValue != null) {
                    readableSprutUnit(unit).takeIf(String::isNotBlank)?.let { append(" ").append(it) }
                }
            }
        }
}

internal fun readableSprutUnit(unit: String): String = when (unit.trim().lowercase(Locale.ROOT)) {
    "" -> ""
    "celsius", "@unit_celsius" -> "°C"
    "percentage", "@unit_percent" -> "%"
    "arcdegrees", "@unit_degree" -> "°"
    "seconds", "@unit_sec" -> "с"
    "milliseconds", "@unit_ms" -> "мс"
    "a", "@unit_amp" -> "А"
    "ma", "@unit_ma" -> "мА"
    "v", "@unit_volt" -> "В"
    "mv", "@unit_mv" -> "мВ"
    "w", "@unit_watt" -> "Вт"
    "kw", "@unit_kw" -> "кВт"
    "mw", "@unit_mw" -> "мВт"
    "va" -> "ВА"
    "var" -> "вар"
    "hz", "@unit_hz" -> "Гц"
    "kwh", "@unit_kwh" -> "кВт·ч"
    "mwh", "@unit_mwh" -> "мВт·ч"
    "kvah" -> "кВА·ч"
    "kvarh" -> "квар·ч"
    "gcal" -> "Гкал"
    "@unit_gcal_h" -> "Гкал/ч"
    "bar", "@unit_bar" -> "бар"
    "@unit_pa" -> "Па"
    "kpa", "@unit_kpa" -> "кПа"
    "@unit_hpa" -> "гПа"
    "mmhg" -> "мм рт. ст."
    "lux", "@unit_lux" -> "лк"
    "uvi" -> "УФ"
    "ppm", "@unit_ppm" -> "ppm"
    "@unit_ppb" -> "ppb"
    "mg_m3", "@unit_mg_m3" -> "мг/м³"
    "@unit_ug_m3" -> "мкг/м³"
    "@unit_g_m3" -> "г/м³"
    "@unit_mg_l" -> "мг/л"
    "m", "@unit_metre" -> "м"
    "@unit_cm" -> "см"
    "@unit_mm" -> "мм"
    "@unit_um" -> "мкм"
    "m2", "@unit_m2" -> "м²"
    "m3", "@unit_m3" -> "м³"
    "@unit_m3h" -> "м³/ч"
    "@unit_m_s" -> "м/с"
    "@unit_litre" -> "л"
    "@unit_ml" -> "мл"
    "@unit_gram" -> "г"
    "@unit_kohm" -> "кОм"
    "@unit_ohm" -> "Ом"
    "@unit_kelvin" -> "К"
    "@unit_bpm" -> "уд/мин"
    "@unit_rpm" -> "об/мин"
    "@unit_day" -> "д"
    "@unit_hour" -> "ч"
    "@unit_min" -> "мин"
    "@unit_kb" -> "КБ"
    "@unit_mbps" -> "Мбит/с"
    "@unit_portion" -> "порц."
    "@unit_times" -> "раз"
    "@unit_ur_h" -> "мкР/ч"
    "db" -> "дБ"
    "db_m" -> "дБм"
    "mired" -> "миред"
    else -> unit
}

internal fun SprutValue.sameValueAs(other: SprutValue): Boolean = when {
    boolValue != null && other.boolValue != null -> boolValue == other.boolValue
    numberValue != null && other.numberValue != null -> abs(numberValue - other.numberValue) < 0.000_001
    stringValue != null && other.stringValue != null -> stringValue.equals(other.stringValue, ignoreCase = true)
    else -> false
}

@Serializable
data class SprutCatalog(
    val controls: List<SprutControl> = emptyList(),
    val rooms: List<SprutRoom> = emptyList(),
    val refreshedAtEpochMs: Long = 0,
    val hubVersion: String = "",
)

@Serializable
data class SprutRoom(
    val id: String,
    val name: String,
)

@Serializable
data class TileAssignment(
    val slot: Int,
    val controlId: String,
)

@Serializable
enum class PanelItemSize {
    COMPACT,
    LARGE,
}

/** One user-selected item in the app-owned Device Controls panel. */
@Serializable
data class PanelItem(
    /**
     * Kept under the legacy JSON name for an in-place upgrade. New values are
     * logical card ids (`service:aId:sId`), while old values may still contain
     * a raw control id and are migrated after the next catalog refresh.
     */
    val controlId: String,
    val size: PanelItemSize = PanelItemSize.COMPACT,
    /** `null` means automatic attributes, an empty list means show none. */
    val attributeControlIds: List<String>? = null,
)

internal fun reconcilePanelSelection(
    current: List<PanelItem>,
    validControlIds: Set<String>,
    replacements: Map<String, String>,
): List<PanelItem> = current.mapNotNull { item ->
    when {
        item.controlId in validControlIds -> item
        replacements[item.controlId] != null -> item.copy(controlId = replacements.getValue(item.controlId))
        else -> null
    }
}.distinctBy(PanelItem::controlId)

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED_LOCAL,
    CONNECTED_CLOUD,
    ERROR,
}

data class ConnectionStatus(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val endpoint: String = "",
    val message: String = "Не проверено",
    val lastSuccessEpochMs: Long? = null,
)

data class DiagnosticEvent(
    val epochMs: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
)

@Serializable
enum class HealthMetric(val title: String, val unit: String, val valueKind: HealthValueKind) {
    STEPS("Шаги сегодня", "шагов", HealthValueKind.INT),
    HEART_RATE("Пульс", "уд/мин", HealthValueKind.DOUBLE),
    RESTING_HEART_RATE("Пульс в покое", "уд/мин", HealthValueKind.DOUBLE),
    SLEEP("Последний сон", "ч", HealthValueKind.DOUBLE),
    WEIGHT("Вес", "кг", HealthValueKind.DOUBLE),
    OXYGEN_SATURATION("Кислород в крови", "%", HealthValueKind.DOUBLE),
    BLOOD_PRESSURE_SYSTOLIC("Давление верхнее", "мм рт. ст.", HealthValueKind.DOUBLE),
    BLOOD_PRESSURE_DIASTOLIC("Давление нижнее", "мм рт. ст.", HealthValueKind.DOUBLE),
    ACTIVE_CALORIES("Активные калории", "ккал", HealthValueKind.DOUBLE),
    DISTANCE("Дистанция сегодня", "км", HealthValueKind.DOUBLE),
    BODY_TEMPERATURE("Температура тела", "°C", HealthValueKind.DOUBLE),
    RESPIRATORY_RATE("Частота дыхания", "вдох/мин", HealthValueKind.DOUBLE),
    HRV("Вариабельность пульса", "мс", HealthValueKind.DOUBLE),
}

@Serializable
enum class HealthValueKind { INT, DOUBLE, STRING, BOOL }

@Serializable
enum class PhoneSensorCategory(val title: String) {
    BATTERY("Аккумулятор"),
    NETWORK("Сеть"),
    DISPLAY("Экран"),
    AUDIO("Звук и режимы"),
    SYSTEM("Система"),
    DIAGNOSTICS("Диагностика"),
}

@Serializable
enum class PhoneUpdateKind(val title: String) {
    EVENT("Событие"),
    POLL("По опросу"),
    EVENT_AND_POLL("Событие + опрос"),
    STATIC("При изменении конфигурации"),
}

@Serializable
enum class PhoneSensorAccess(val title: String, val description: String) {
    NONE("Не требуется", "Использует обычные системные данные Android"),
    NOTIFICATION_POLICY(
        "Режим «Не беспокоить»",
        "Android выдаёт этот специальный доступ на отдельном системном экране",
    ),
}

/**
 * Phone information that can be exposed as a separate virtual SprutHub device.
 *
 * The first set intentionally uses Android APIs that do not need dangerous
 * runtime permissions. More sensitive sensors (location, SIM, Bluetooth and
 * Wi-Fi identity) can then be added without weakening the permission model.
 */
@Serializable
enum class PhoneSensor(
    val title: String,
    val description: String,
    val unit: String,
    val valueKind: HealthValueKind,
    val category: PhoneSensorCategory,
    val updateKind: PhoneUpdateKind,
    val access: PhoneSensorAccess = PhoneSensorAccess.NONE,
    val minimumApi: Int = 30,
) {
    BATTERY_LEVEL(
        "Заряд аккумулятора",
        "Текущий уровень заряда",
        "%",
        HealthValueKind.INT,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    IS_CHARGING(
        "Подключена зарядка",
        "Меняется сразу при подключении и отключении питания",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT,
    ),
    BATTERY_STATE(
        "Состояние зарядки",
        "Заряжается, полностью заряжен, разряжается или питание подключено без зарядки",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT,
    ),
    CHARGER_TYPE(
        "Тип зарядки",
        "USB, сеть, беспроводная зарядка или док-станция",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT,
    ),
    BATTERY_TEMPERATURE(
        "Температура аккумулятора",
        "Температура, которую сообщает контроллер батареи",
        "°C",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    BATTERY_HEALTH(
        "Состояние аккумулятора",
        "Оценка Android: хорошее, перегрев, холод и другие состояния",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    BATTERY_VOLTAGE(
        "Напряжение аккумулятора",
        "Напряжение по данным системного контроллера",
        "мВ",
        HealthValueKind.INT,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    BATTERY_CURRENT(
        "Ток аккумулятора",
        "Мгновенный ток по данным контроллера; знак и точность зависят от производителя",
        "мА",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    BATTERY_POWER(
        "Мощность аккумулятора",
        "Расчёт по току и напряжению; знак показывает направление, если прошивка его сообщает корректно",
        "Вт",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    CHARGE_TIME_REMAINING(
        "До полной зарядки",
        "Оценка Android; поле временно не обновляется, если система не может рассчитать остаток",
        "мин",
        HealthValueKind.INT,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    BATTERY_CYCLE_COUNT(
        "Циклы аккумулятора",
        "Количество полных циклов зарядки, если контроллер телефона сообщает его Android",
        "циклов",
        HealthValueKind.INT,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT_AND_POLL,
        minimumApi = 34,
    ),
    POWER_SAVE_MODE(
        "Энергосбережение",
        "Включён ли системный режим экономии энергии",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.BATTERY,
        PhoneUpdateKind.EVENT,
    ),
    CONNECTION_TYPE(
        "Тип подключения",
        "Wi‑Fi, мобильная сеть, Ethernet, VPN или нет сети",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.NETWORK,
        PhoneUpdateKind.EVENT,
    ),
    NETWORK_METERED(
        "Лимитная сеть",
        "Считает ли Android текущее подключение тарифицируемым",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.NETWORK,
        PhoneUpdateKind.EVENT,
    ),
    NETWORK_VALIDATED(
        "Интернет доступен",
        "Подтвердил ли Android выход в интернет",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.NETWORK,
        PhoneUpdateKind.EVENT,
    ),
    LOCAL_IP(
        "Локальный IP",
        "Адрес телефона в текущей сети без обращения к внешним сервисам",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.NETWORK,
        PhoneUpdateKind.EVENT,
    ),
    SCREEN_BRIGHTNESS(
        "Яркость экрана",
        "Текущее системное значение яркости",
        "%",
        HealthValueKind.INT,
        PhoneSensorCategory.DISPLAY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    SCREEN_BRIGHTNESS_AUTO(
        "Автояркость",
        "Включена ли автоматическая регулировка яркости",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.DISPLAY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    SCREEN_TIMEOUT(
        "Тайм-аут экрана",
        "Через сколько секунд бездействия Android выключает экран",
        "с",
        HealthValueKind.INT,
        PhoneSensorCategory.DISPLAY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    SCREEN_ORIENTATION(
        "Ориентация экрана",
        "Книжная, альбомная или неопределённая",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.DISPLAY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    SCREEN_ROTATION(
        "Поворот экрана",
        "Фактический поворот дисплея относительно естественного положения",
        "°",
        HealthValueKind.INT,
        PhoneSensorCategory.DISPLAY,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    RINGER_MODE(
        "Режим звонка",
        "Звук, вибрация или без звука",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.AUDIO,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    DND_MODE(
        "Не беспокоить",
        "Текущий системный фильтр уведомлений",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.AUDIO,
        PhoneUpdateKind.EVENT,
        access = PhoneSensorAccess.NOTIFICATION_POLICY,
    ),
    MUSIC_ACTIVE(
        "Воспроизводится музыка",
        "Сообщает ли Android об активном воспроизведении мультимедиа",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.AUDIO,
        PhoneUpdateKind.POLL,
    ),
    MICROPHONE_MUTED(
        "Микрофон отключён",
        "Текущее состояние системного отключения микрофона",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.AUDIO,
        PhoneUpdateKind.POLL,
    ),
    MEDIA_VOLUME(
        "Громкость мультимедиа",
        "Текущая громкость потока музыки относительно максимальной",
        "%",
        HealthValueKind.INT,
        PhoneSensorCategory.AUDIO,
        PhoneUpdateKind.POLL,
    ),
    DEVICE_MODEL(
        "Модель телефона",
        "Производитель и модель Android-устройства",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.STATIC,
    ),
    ANDROID_VERSION(
        "Версия Android",
        "Версия системы и уровень Android API",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.STATIC,
    ),
    SECURITY_PATCH(
        "Патч безопасности",
        "Дата установленного патча безопасности Android",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.STATIC,
    ),
    APP_VERSION(
        "Версия SprutHub Helper",
        "Установленная версия приложения",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.STATIC,
    ),
    SCREEN_INTERACTIVE(
        "Экран активен",
        "Включён ли экран и может ли пользователь взаимодействовать с телефоном",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.EVENT,
    ),
    DEVICE_IDLE(
        "Режим Doze",
        "Перевёл ли Android телефон в глубокий режим ожидания",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.EVENT,
    ),
    TIME_ZONE(
        "Часовой пояс",
        "Текущий системный часовой пояс",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.EVENT,
    ),
    NEXT_ALARM(
        "Следующий будильник",
        "Время следующего системного будильника; не обновляется, если будильник не задан",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.SYSTEM,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    UPTIME_HOURS(
        "Время работы",
        "Сколько часов прошло с последней загрузки телефона",
        "ч",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    FREE_STORAGE_GB(
        "Свободное хранилище",
        "Доступное место во внутреннем хранилище",
        "ГБ",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    TOTAL_STORAGE_GB(
        "Объём хранилища",
        "Полный объём раздела данных",
        "ГБ",
        HealthValueKind.DOUBLE,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    STORAGE_USED_PERCENT(
        "Хранилище занято",
        "Доля занятого внутреннего хранилища",
        "%",
        HealthValueKind.INT,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    AVAILABLE_MEMORY_MB(
        "Свободная память",
        "Оперативная память, доступная приложениям по оценке Android",
        "МБ",
        HealthValueKind.INT,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    TOTAL_MEMORY_MB(
        "Всего памяти",
        "Объём оперативной памяти, доступный Android",
        "МБ",
        HealthValueKind.INT,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    LOW_MEMORY(
        "Мало памяти",
        "Системный сигнал Android о нехватке оперативной памяти",
        "да/нет",
        HealthValueKind.BOOL,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.POLL,
    ),
    SYNC_HEARTBEAT(
        "Пульс синхронизации",
        "Служебное число меняется после каждой отправки и позволяет SprutHub заметить остановку приложения",
        "мин Unix",
        HealthValueKind.INT,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
    LAST_SYNC(
        "Последняя синхронизация",
        "Время последней отправки данных в SprutHub",
        "",
        HealthValueKind.STRING,
        PhoneSensorCategory.DIAGNOSTICS,
        PhoneUpdateKind.EVENT_AND_POLL,
    ),
}

/** Operational fields required for reliable recovery and hub-side monitoring. */
val REQUIRED_PHONE_SENSORS: Set<PhoneSensor> = setOf(PhoneSensor.SYNC_HEARTBEAT)

fun withRequiredPhoneSensors(sensors: Set<PhoneSensor>): Set<PhoneSensor> = sensors + REQUIRED_PHONE_SENSORS

const val PHONE_HEARTBEAT_SCENARIO_NAME = "SprutHub Helper · Контроль телефона"

@Serializable
enum class PhoneSyncMode(val title: String, val description: String) {
    BALANCED(
        "Сбалансированный",
        "Системная синхронизация примерно раз в 15 минут; Android может отложить запуск",
    ),
    LIVE(
        "Постоянное подключение",
        "События отправляются сразу, пока видно постоянное уведомление",
    ),
}

@Serializable
enum class PhonePollInterval(val minutes: Int, val title: String) {
    ONE_MINUTE(1, "1 мин"),
    FIVE_MINUTES(5, "5 мин"),
    FIFTEEN_MINUTES(15, "15 мин"),
}

data class PhoneSyncSettings(
    val enabled: Boolean = false,
    val mode: PhoneSyncMode = PhoneSyncMode.BALANCED,
    val pollInterval: PhonePollInterval = PhonePollInterval.FIVE_MINUTES,
    val watchdogEnabled: Boolean = true,
)

@Serializable
data class HealthTarget(
    val key: String,
    val serviceId: String,
    val characteristicId: String,
    val valueField: String,
    /** SprutHub type identifiers used by block scenarios. Empty for bindings saved by older builds. */
    val serviceType: String = "",
    val characteristicType: String = "",
)

@Serializable
data class HealthDeviceBinding(
    val accessoryId: String,
    val name: String,
    val roomId: String,
    val targets: List<HealthTarget>,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

private fun Double.formatCompact(): String = if (this % 1.0 == 0.0) {
    toLong().toString()
} else {
    "%.1f".format(this)
}
