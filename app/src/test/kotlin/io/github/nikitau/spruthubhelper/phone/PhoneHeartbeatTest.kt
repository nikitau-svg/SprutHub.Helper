package io.github.nikitau.spruthubhelper.phone

import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.withRequiredPhoneSensors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHeartbeatTest {
    @Test
    fun `heartbeat uses stable Unix minutes`() {
        assertEquals(0L, heartbeatMinute(59_999L))
        assertEquals(1L, heartbeatMinute(60_000L))
        assertEquals(45L, heartbeatMinute(45 * 60_000L))
    }

    @Test
    fun `heartbeat cannot be removed from phone selection`() {
        val selected = withRequiredPhoneSensors(setOf(PhoneSensor.BATTERY_LEVEL))

        assertTrue(PhoneSensor.BATTERY_LEVEL in selected)
        assertTrue(PhoneSensor.SYNC_HEARTBEAT in selected)
    }

    @Test
    fun `recreated phone IDs require immediate scenario rebinding`() {
        fun binding(accessoryId: String, serviceId: String, characteristicId: String) = HealthDeviceBinding(
            accessoryId = accessoryId,
            name = "Телефон",
            roomId = "1",
            targets = listOf(
                HealthTarget(
                    key = PhoneSensor.SYNC_HEARTBEAT.name,
                    serviceId = serviceId,
                    characteristicId = characteristicId,
                    valueField = "intValue",
                ),
            ),
        )

        assertTrue(heartbeatBindingChanged(binding("1", "2", "3"), binding("4", "5", "6")))
        assertTrue(!heartbeatBindingChanged(binding("1", "2", "3"), binding("1", "2", "3")))
    }
}
