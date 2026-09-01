package io.github.nikitau.spruthubhelper.phone

import android.content.Intent
import android.os.BatteryManager

/**
 * Stable battery values that SprutHub Helper promises to publish immediately.
 *
 * Samsung can send ACTION_BATTERY_CHANGED every few seconds while charging
 * because current, voltage and the remaining-time estimate fluctuate. Those
 * continuously changing measurements belong to the configured safety poll;
 * they must not start a complete phone sync for every raw broadcast.
 */
internal data class PhoneBatteryFingerprint(
    val level: Int,
    val scale: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val cycleCount: Int,
)

internal fun Intent.phoneBatteryFingerprint(): PhoneBatteryFingerprint = PhoneBatteryFingerprint(
    level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
    scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1),
    status = getIntExtra(BatteryManager.EXTRA_STATUS, -1),
    plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
    health = getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
    cycleCount = getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1),
)

internal fun filterPhoneBatteryTriggers(
    triggers: Set<PhoneSyncTrigger>,
    batteryChanged: Boolean,
): Set<PhoneSyncTrigger> = if (batteryChanged) {
    triggers
} else {
    triggers.filterTo(linkedSetOf()) { it != PhoneSyncTrigger.BATTERY_CHANGED }
}

internal class PhoneBatteryChangeGate {
    private var initialized = false
    private var committed: PhoneBatteryFingerprint? = null

    @Synchronized
    fun prime(fingerprint: PhoneBatteryFingerprint) {
        committed = fingerprint
        initialized = true
    }

    @Synchronized
    fun hasChanged(fingerprint: PhoneBatteryFingerprint): Boolean =
        !initialized || committed != fingerprint

    @Synchronized
    fun commit(fingerprint: PhoneBatteryFingerprint) {
        committed = fingerprint
        initialized = true
    }
}
