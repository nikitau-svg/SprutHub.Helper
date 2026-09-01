package io.github.nikitau.spruthubhelper.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneBatteryChangeGateTest {
    private val charging = PhoneBatteryFingerprint(
        level = 97,
        scale = 100,
        status = 2,
        plugged = 2,
        health = 2,
        cycleCount = 0,
    )

    @Test
    fun `primed state ignores repeated battery broadcasts`() {
        val gate = PhoneBatteryChangeGate().apply { prime(charging) }

        repeat(20) { assertFalse(gate.hasChanged(charging)) }
    }

    @Test
    fun `failed delivery does not consume a real battery change`() {
        val gate = PhoneBatteryChangeGate().apply { prime(charging) }
        val changed = charging.copy(level = 98)

        assertTrue(gate.hasChanged(changed))
        assertTrue(gate.hasChanged(changed))

        gate.commit(changed)
        assertFalse(gate.hasChanged(changed))
    }

    @Test
    fun `every immediate battery field is part of the fingerprint`() {
        val changedStates = listOf(
            charging.copy(level = 98),
            charging.copy(scale = 200),
            charging.copy(status = 5),
            charging.copy(plugged = 0),
            charging.copy(health = 3),
            charging.copy(cycleCount = 1),
        )

        changedStates.forEach { changed ->
            val gate = PhoneBatteryChangeGate().apply { prime(charging) }
            assertTrue(changed.toString(), gate.hasChanged(changed))
        }
    }

    @Test
    fun `unprimed gate treats the first observation as a change`() {
        assertTrue(PhoneBatteryChangeGate().hasChanged(charging))
    }

    @Test
    fun `unchanged battery callback is removed without dropping another event`() {
        val mixed = linkedSetOf(
            PhoneSyncTrigger.BATTERY_CHANGED,
            PhoneSyncTrigger.SCREEN_OFF,
        )

        assertEquals(
            setOf(PhoneSyncTrigger.SCREEN_OFF),
            filterPhoneBatteryTriggers(mixed, batteryChanged = false),
        )
        assertEquals(mixed, filterPhoneBatteryTriggers(mixed, batteryChanged = true))
        assertTrue(
            filterPhoneBatteryTriggers(
                setOf(PhoneSyncTrigger.BATTERY_CHANGED),
                batteryChanged = false,
            ).isEmpty(),
        )
    }
}
