package io.github.nikitau.spruthubhelper.phone

import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSensorAccess
import io.github.nikitau.spruthubhelper.data.PhoneSensorCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSensorAccessTest {
    @Test
    fun `only selected protected sensors request special access`() {
        assertTrue(requiredPhoneSensorAccesses(setOf(PhoneSensor.BATTERY_LEVEL)).isEmpty())
        assertEquals(
            setOf(PhoneSensorAccess.NOTIFICATION_POLICY),
            requiredPhoneSensorAccesses(setOf(PhoneSensor.BATTERY_LEVEL, PhoneSensor.DND_MODE)),
        )
    }

    @Test
    fun `cycle count is hidden on old Android but available on Android 14`() {
        val selected = setOf(PhoneSensor.BATTERY_LEVEL, PhoneSensor.BATTERY_CYCLE_COUNT)

        assertEquals(setOf(PhoneSensor.BATTERY_CYCLE_COUNT), unsupportedPhoneSensors(selected, api = 33))
        assertTrue(unsupportedPhoneSensors(selected, api = 34).isEmpty())
    }

    @Test
    fun `new phone groups have stable unique labels`() {
        assertTrue(PhoneSensor.entries.map(PhoneSensor::title).let { it.size == it.toSet().size })
        assertTrue(PhoneSensor.entries.any { it.category == PhoneSensorCategory.DISPLAY })
        assertTrue(PhoneSensor.entries.any { it.category == PhoneSensorCategory.AUDIO })
    }

    @Test
    fun `readiness explains missing access before synchronization`() {
        assertEquals(
            "Сначала разрешите: Режим «Не беспокоить»",
            phoneSensorReadinessError(
                missingAccesses = setOf(PhoneSensorAccess.NOTIFICATION_POLICY),
                unsupportedSensors = emptySet(),
            ),
        )
    }
}
