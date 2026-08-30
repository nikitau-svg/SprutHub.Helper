package io.github.nikitau.spruthubhelper.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
        "HubConfig(mode=$mode, localUrl=$localUrl, cloudUrl=$cloudUrl, serial=$serial, " +
            "email=$email, localPassword=<redacted>, cloudPassword=<redacted>, password=<redacted>)"

    companion object {
        const val DEFAULT_LOCAL_URL = "ws://192.168.1.135/spruthub"
        const val DEFAULT_CLOUD_URL = "wss://beta.spruthub.com/spruthub"
        const val DEFAULT_SERIAL = "68C341B253468E4B"
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
    fun asBoolean(): Boolean = boolValue
        ?: numberValue?.let { it > 0.0 }
        ?: stringValue?.let { it.equals("true", true) || it == "1" || it.equals("on", true) }
        ?: false

    fun asDouble(): Double = numberValue
        ?: if (boolValue == true) 1.0 else 0.0
}

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
    val valueField: String = "boolValue",
    val rangeValueField: String = "doubleValue",
) {
    val displayValue: String
        get() = when (behavior) {
            ControlBehavior.TOGGLE, ControlBehavior.TOGGLE_RANGE -> if (value.asBoolean()) "Включено" else "Выключено"
            ControlBehavior.RANGE -> buildString {
                append(value.asDouble().formatCompact())
                if (unit.isNotBlank()) append(" ").append(unit)
            }
            ControlBehavior.BUTTON -> "Готово к запуску"
            ControlBehavior.SENSOR -> value.stringValue
                ?: value.numberValue?.formatCompact()
                ?: value.boolValue?.toString()
                ?: "—"
        }
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
data class HealthTarget(
    val key: String,
    val serviceId: String,
    val characteristicId: String,
    val valueField: String,
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
