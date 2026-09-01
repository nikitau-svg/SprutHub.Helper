package io.github.nikitau.spruthubhelper.phone

import io.github.nikitau.spruthubhelper.data.PhoneSensor

private val BATTERY_EVENT_SENSORS = eventSensors(
    PhoneSensor.BATTERY_LEVEL,
    PhoneSensor.IS_CHARGING,
    PhoneSensor.BATTERY_STATE,
    PhoneSensor.CHARGER_TYPE,
    PhoneSensor.BATTERY_HEALTH,
    PhoneSensor.BATTERY_CYCLE_COUNT,
)
private val CHARGING_EVENT_SENSORS = eventSensors(
    PhoneSensor.IS_CHARGING,
    PhoneSensor.BATTERY_STATE,
    PhoneSensor.CHARGER_TYPE,
    PhoneSensor.BATTERY_CURRENT,
    PhoneSensor.BATTERY_POWER,
    PhoneSensor.CHARGE_TIME_REMAINING,
)
private val NETWORK_EVENT_SENSORS = eventSensors(
    PhoneSensor.CONNECTION_TYPE,
    PhoneSensor.NETWORK_METERED,
    PhoneSensor.NETWORK_VALIDATED,
    PhoneSensor.LOCAL_IP,
)
private val DISPLAY_EVENT_SENSORS = eventSensors(
    PhoneSensor.SCREEN_BRIGHTNESS,
    PhoneSensor.SCREEN_BRIGHTNESS_AUTO,
    PhoneSensor.SCREEN_TIMEOUT,
    PhoneSensor.SCREEN_ORIENTATION,
    PhoneSensor.SCREEN_ROTATION,
)
private val AUDIO_EVENT_SENSORS = eventSensors(
    PhoneSensor.RINGER_MODE,
    PhoneSensor.DND_MODE,
)

private fun eventSensors(vararg sensors: PhoneSensor): Set<PhoneSensor> =
    sensors.toSet()

/**
 * An auditable map between Android callbacks and the phone values they can
 * change. A null [affectedSensors] means that the trigger requests a complete
 * control snapshot rather than an event-filtered one.
 */
internal enum class PhoneSyncTrigger(
    val reason: String,
    val cadence: PhoneSyncCadence,
    val affectedSensors: Set<PhoneSensor>?,
) {
    MONITOR_STARTED("monitor-started", PhoneSyncCadence.IMMEDIATE, null),
    SETTINGS_CHANGED("settings-changed", PhoneSyncCadence.IMMEDIATE, null),
    BATTERY_CHANGED("battery-changed", PhoneSyncCadence.IMMEDIATE, BATTERY_EVENT_SENSORS),
    POWER_CONNECTED("power-connected", PhoneSyncCadence.IMMEDIATE, CHARGING_EVENT_SENSORS),
    POWER_DISCONNECTED("power-disconnected", PhoneSyncCadence.IMMEDIATE, CHARGING_EVENT_SENSORS),
    SCREEN_ON("screen-on", PhoneSyncCadence.IMMEDIATE, eventSensors(PhoneSensor.SCREEN_INTERACTIVE)),
    SCREEN_OFF("screen-off", PhoneSyncCadence.IMMEDIATE, eventSensors(PhoneSensor.SCREEN_INTERACTIVE)),
    TIME_ZONE_CHANGED("time-zone-changed", PhoneSyncCadence.IMMEDIATE, eventSensors(PhoneSensor.TIME_ZONE)),
    POWER_SAVE_MODE_CHANGED(
        "power-save-mode-changed",
        PhoneSyncCadence.IMMEDIATE,
        eventSensors(PhoneSensor.POWER_SAVE_MODE),
    ),
    DEVICE_IDLE_MODE_CHANGED(
        "device-idle-mode-changed",
        PhoneSyncCadence.IMMEDIATE,
        eventSensors(PhoneSensor.DEVICE_IDLE),
    ),
    DISPLAY_SETTINGS_CHANGED("display-settings-changed", PhoneSyncCadence.IMMEDIATE, DISPLAY_EVENT_SENSORS),
    CONFIGURATION_CHANGED("configuration-changed", PhoneSyncCadence.IMMEDIATE, DISPLAY_EVENT_SENSORS),
    RINGER_MODE_CHANGED("ringer-mode-changed", PhoneSyncCadence.IMMEDIATE, AUDIO_EVENT_SENSORS),
    DND_MODE_CHANGED("dnd-mode-changed", PhoneSyncCadence.IMMEDIATE, AUDIO_EVENT_SENSORS),
    NEXT_ALARM_CHANGED(
        "next-alarm-changed",
        PhoneSyncCadence.IMMEDIATE,
        eventSensors(PhoneSensor.NEXT_ALARM),
    ),
    NETWORK_AVAILABLE("network-available", PhoneSyncCadence.IMMEDIATE, NETWORK_EVENT_SENSORS),
    NETWORK_LOST("network-lost", PhoneSyncCadence.IMMEDIATE, NETWORK_EVENT_SENSORS),
    NETWORK_CAPABILITIES_CHANGED(
        "network-capabilities-changed",
        PhoneSyncCadence.IMMEDIATE,
        NETWORK_EVENT_SENSORS,
    ),
    NETWORK_ADDRESS_CHANGED("network-address-changed", PhoneSyncCadence.IMMEDIATE, NETWORK_EVENT_SENSORS),
    FOREGROUND_POLL("foreground-poll", PhoneSyncCadence.FOREGROUND_POLL, null),
    WORK_MANAGER_PERIODIC("work-manager-15-minute", PhoneSyncCadence.WORK_MANAGER_PERIODIC, null),
    ;
}

internal enum class PhoneSyncCadence {
    IMMEDIATE,
    FOREGROUND_POLL,
    WORK_MANAGER_PERIODIC,
}

internal data class PhoneEventSyncDecision(
    val shouldSync: Boolean,
    val matchedSensors: Set<PhoneSensor>,
    val skipReason: String? = null,
)

internal object PhoneEventSyncPolicy {
    /** Keeps Android action strings in one JVM-testable mapping. */
    fun fromBroadcastAction(action: String?): PhoneSyncTrigger? = when (action) {
        ACTION_BATTERY_CHANGED -> PhoneSyncTrigger.BATTERY_CHANGED
        ACTION_POWER_CONNECTED -> PhoneSyncTrigger.POWER_CONNECTED
        ACTION_POWER_DISCONNECTED -> PhoneSyncTrigger.POWER_DISCONNECTED
        ACTION_SCREEN_ON -> PhoneSyncTrigger.SCREEN_ON
        ACTION_SCREEN_OFF -> PhoneSyncTrigger.SCREEN_OFF
        ACTION_TIMEZONE_CHANGED -> PhoneSyncTrigger.TIME_ZONE_CHANGED
        ACTION_POWER_SAVE_MODE_CHANGED -> PhoneSyncTrigger.POWER_SAVE_MODE_CHANGED
        ACTION_DEVICE_IDLE_MODE_CHANGED -> PhoneSyncTrigger.DEVICE_IDLE_MODE_CHANGED
        ACTION_CONFIGURATION_CHANGED -> PhoneSyncTrigger.CONFIGURATION_CHANGED
        ACTION_RINGER_MODE_CHANGED -> PhoneSyncTrigger.RINGER_MODE_CHANGED
        ACTION_INTERRUPTION_FILTER_CHANGED -> PhoneSyncTrigger.DND_MODE_CHANGED
        ACTION_NEXT_ALARM_CLOCK_CHANGED -> PhoneSyncTrigger.NEXT_ALARM_CHANGED
        else -> null
    }

    fun decide(
        triggers: Set<PhoneSyncTrigger>,
        selectedSensors: Set<PhoneSensor>,
    ): PhoneEventSyncDecision {
        if (selectedSensors.isEmpty()) {
            return PhoneEventSyncDecision(
                shouldSync = false,
                matchedSensors = emptySet(),
                skipReason = "no-selected-phone-sensors",
            )
        }
        if (triggers.any { it.affectedSensors == null }) {
            return PhoneEventSyncDecision(shouldSync = true, matchedSensors = selectedSensors)
        }
        val affected = triggers.flatMapTo(mutableSetOf()) { it.affectedSensors.orEmpty() }
        val matched = selectedSensors intersect affected
        return if (matched.isNotEmpty()) {
            PhoneEventSyncDecision(shouldSync = true, matchedSensors = matched)
        } else {
            PhoneEventSyncDecision(
                shouldSync = false,
                matchedSensors = emptySet(),
                skipReason = "selected-phone-sensors-unaffected",
            )
        }
    }

    private const val ACTION_BATTERY_CHANGED = "android.intent.action.BATTERY_CHANGED"
    private const val ACTION_POWER_CONNECTED = "android.intent.action.ACTION_POWER_CONNECTED"
    private const val ACTION_POWER_DISCONNECTED = "android.intent.action.ACTION_POWER_DISCONNECTED"
    private const val ACTION_SCREEN_ON = "android.intent.action.SCREEN_ON"
    private const val ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF"
    private const val ACTION_TIMEZONE_CHANGED = "android.intent.action.TIMEZONE_CHANGED"
    private const val ACTION_POWER_SAVE_MODE_CHANGED = "android.os.action.POWER_SAVE_MODE_CHANGED"
    private const val ACTION_DEVICE_IDLE_MODE_CHANGED = "android.os.action.DEVICE_IDLE_MODE_CHANGED"
    private const val ACTION_CONFIGURATION_CHANGED = "android.intent.action.CONFIGURATION_CHANGED"
    private const val ACTION_RINGER_MODE_CHANGED = "android.media.RINGER_MODE_CHANGED"
    private const val ACTION_INTERRUPTION_FILTER_CHANGED = "android.app.action.INTERRUPTION_FILTER_CHANGED"
    private const val ACTION_NEXT_ALARM_CLOCK_CHANGED = "android.app.action.NEXT_ALARM_CLOCK_CHANGED"
}
