package io.github.nikitau.spruthubhelper.phone

import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.withRequiredPhoneSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneEventSyncPolicyTest {
    @Test
    fun `maps every registered phone broadcast to an immediate trigger`() {
        val expected = mapOf(
            "android.intent.action.BATTERY_CHANGED" to PhoneSyncTrigger.BATTERY_CHANGED,
            "android.intent.action.ACTION_POWER_CONNECTED" to PhoneSyncTrigger.POWER_CONNECTED,
            "android.intent.action.ACTION_POWER_DISCONNECTED" to PhoneSyncTrigger.POWER_DISCONNECTED,
            "android.intent.action.SCREEN_ON" to PhoneSyncTrigger.SCREEN_ON,
            "android.intent.action.SCREEN_OFF" to PhoneSyncTrigger.SCREEN_OFF,
            "android.intent.action.TIMEZONE_CHANGED" to PhoneSyncTrigger.TIME_ZONE_CHANGED,
            "android.os.action.POWER_SAVE_MODE_CHANGED" to PhoneSyncTrigger.POWER_SAVE_MODE_CHANGED,
            "android.os.action.DEVICE_IDLE_MODE_CHANGED" to PhoneSyncTrigger.DEVICE_IDLE_MODE_CHANGED,
            "android.intent.action.CONFIGURATION_CHANGED" to PhoneSyncTrigger.CONFIGURATION_CHANGED,
            "android.media.RINGER_MODE_CHANGED" to PhoneSyncTrigger.RINGER_MODE_CHANGED,
            "android.app.action.INTERRUPTION_FILTER_CHANGED" to PhoneSyncTrigger.DND_MODE_CHANGED,
            "android.app.action.NEXT_ALARM_CLOCK_CHANGED" to PhoneSyncTrigger.NEXT_ALARM_CHANGED,
        )

        expected.forEach { (action, trigger) ->
            assertEquals(trigger, PhoneEventSyncPolicy.fromBroadcastAction(action))
            assertEquals(PhoneSyncCadence.IMMEDIATE, trigger.cadence)
        }
    }

    @Test
    fun `ignores an unknown or missing broadcast`() {
        assertNull(PhoneEventSyncPolicy.fromBroadcastAction(null))
        assertNull(PhoneEventSyncPolicy.fromBroadcastAction("example.UNKNOWN"))
    }

    @Test
    fun `charging event runs only for an affected selected sensor`() {
        val relevant = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.POWER_CONNECTED),
            selectedSensors = withRequiredPhoneSensors(
                setOf(PhoneSensor.IS_CHARGING, PhoneSensor.UPTIME_HOURS),
            ),
        )
        val unrelated = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.POWER_CONNECTED),
            selectedSensors = withRequiredPhoneSensors(setOf(PhoneSensor.UPTIME_HOURS)),
        )

        assertTrue(relevant.shouldSync)
        assertEquals(setOf(PhoneSensor.IS_CHARGING), relevant.matchedSensors)
        assertFalse(unrelated.shouldSync)
        assertEquals("selected-phone-sensors-unaffected", unrelated.skipReason)
    }

    @Test
    fun `raw battery broadcasts do not push continuously varying electrical readings`() {
        val stable = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.BATTERY_CHANGED),
            selectedSensors = withRequiredPhoneSensors(setOf(PhoneSensor.BATTERY_LEVEL)),
        )
        val varying = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.BATTERY_CHANGED),
            selectedSensors = withRequiredPhoneSensors(
                setOf(
                    PhoneSensor.BATTERY_CURRENT,
                    PhoneSensor.BATTERY_POWER,
                    PhoneSensor.CHARGE_TIME_REMAINING,
                ),
            ),
        )
        val connected = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.POWER_CONNECTED),
            selectedSensors = withRequiredPhoneSensors(setOf(PhoneSensor.BATTERY_CURRENT)),
        )

        assertTrue(stable.shouldSync)
        assertFalse(varying.shouldSync)
        assertEquals("selected-phone-sensors-unaffected", varying.skipReason)
        assertTrue(connected.shouldSync)
    }

    @Test
    fun `network callbacks include all network fields so address changes are not missed`() {
        val selected = setOf(
            PhoneSensor.CONNECTION_TYPE,
            PhoneSensor.NETWORK_METERED,
            PhoneSensor.NETWORK_VALIDATED,
            PhoneSensor.LOCAL_IP,
        )

        listOf(
            PhoneSyncTrigger.NETWORK_AVAILABLE,
            PhoneSyncTrigger.NETWORK_LOST,
            PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED,
            PhoneSyncTrigger.NETWORK_ADDRESS_CHANGED,
        ).forEach { trigger ->
            val decision = PhoneEventSyncPolicy.decide(setOf(trigger), selected)
            assertTrue(trigger.reason, decision.shouldSync)
            assertEquals(selected, decision.matchedSensors)
        }
    }

    @Test
    fun `screen and power events do not wake a phone with only poll sensors selected`() {
        val selected = withRequiredPhoneSensors(
            setOf(PhoneSensor.UPTIME_HOURS, PhoneSensor.FREE_STORAGE_GB),
        )

        listOf(
            PhoneSyncTrigger.SCREEN_ON,
            PhoneSyncTrigger.SCREEN_OFF,
            PhoneSyncTrigger.POWER_SAVE_MODE_CHANGED,
            PhoneSyncTrigger.DEVICE_IDLE_MODE_CHANGED,
        ).forEach { trigger ->
            assertFalse(trigger.reason, PhoneEventSyncPolicy.decide(setOf(trigger), selected).shouldSync)
        }
    }

    @Test
    fun `display audio and alarm events target their own selected fields`() {
        val cases = mapOf(
            PhoneSyncTrigger.DISPLAY_SETTINGS_CHANGED to PhoneSensor.SCREEN_BRIGHTNESS,
            PhoneSyncTrigger.CONFIGURATION_CHANGED to PhoneSensor.SCREEN_ROTATION,
            PhoneSyncTrigger.RINGER_MODE_CHANGED to PhoneSensor.RINGER_MODE,
            PhoneSyncTrigger.DND_MODE_CHANGED to PhoneSensor.DND_MODE,
            PhoneSyncTrigger.NEXT_ALARM_CHANGED to PhoneSensor.NEXT_ALARM,
        )

        cases.forEach { (trigger, sensor) ->
            val decision = PhoneEventSyncPolicy.decide(setOf(trigger), setOf(sensor))
            assertTrue(trigger.reason, decision.shouldSync)
            assertEquals(setOf(sensor), decision.matchedSensors)
        }
    }

    @Test
    fun `monitor start foreground poll and 15 minute worker request a full snapshot`() {
        val selected = setOf(PhoneSensor.SCREEN_INTERACTIVE, PhoneSensor.UPTIME_HOURS)

        listOf(
            PhoneSyncTrigger.MONITOR_STARTED,
            PhoneSyncTrigger.FOREGROUND_POLL,
            PhoneSyncTrigger.WORK_MANAGER_PERIODIC,
        ).forEach { trigger ->
            val decision = PhoneEventSyncPolicy.decide(setOf(trigger), selected)
            assertTrue(trigger.reason, decision.shouldSync)
            assertEquals(selected, decision.matchedSensors)
        }
        assertEquals(PhoneSyncCadence.WORK_MANAGER_PERIODIC, PhoneSyncTrigger.WORK_MANAGER_PERIODIC.cadence)
    }

    @Test
    fun `empty sensor selection has a stable skip reason`() {
        val decision = PhoneEventSyncPolicy.decide(
            triggers = setOf(PhoneSyncTrigger.MONITOR_STARTED),
            selectedSensors = emptySet(),
        )

        assertFalse(decision.shouldSync)
        assertEquals("no-selected-phone-sensors", decision.skipReason)
    }
}
