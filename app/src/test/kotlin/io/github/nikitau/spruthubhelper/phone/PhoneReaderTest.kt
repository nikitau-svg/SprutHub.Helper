package io.github.nikitau.spruthubhelper.phone

import android.app.NotificationManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.BatteryManager
import android.view.Surface
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.health.HealthReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneReaderTest {
    @Test
    fun `battery state and charger labels are stable`() {
        assertEquals("Заряжается", batteryState(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals("Полностью заряжен", batteryState(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals("Разряжается", batteryState(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals("USB", chargerType(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("Не подключена", chargerType(0))
    }

    @Test
    fun `battery power preserves the current direction`() {
        assertEquals(4.0, batteryPowerWatts(1_000_000.0, 4_000)!!, 0.0001)
        assertEquals(-2.0, batteryPowerWatts(-500_000.0, 4_000)!!, 0.0001)
        assertNull(batteryPowerWatts(null, 4_000))
        assertNull(batteryPowerWatts(1_000_000.0, null))
    }

    @Test
    fun `display and volume values are normalized`() {
        assertEquals(50, percent(128, 255))
        assertEquals(0, percent(10, 0))
        assertEquals("Книжная", screenOrientation(Configuration.ORIENTATION_PORTRAIT))
        assertEquals("Альбомная", screenOrientation(Configuration.ORIENTATION_LANDSCAPE))
        assertEquals(270, rotationDegrees(Surface.ROTATION_270))
    }

    @Test
    fun `audio modes use human readable labels`() {
        assertEquals("Вибрация", ringerMode(AudioManager.RINGER_MODE_VIBRATE))
        assertEquals(
            "Только будильники",
            interruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS),
        )
    }

    @Test
    fun `storage percentage is bounded`() {
        assertEquals(75, storageUsedPercent(25, 100))
        assertEquals(100, storageUsedPercent(-1, 100))
        assertEquals(0, storageUsedPercent(10, 0))
    }

    @Test
    fun `selected sensor preview includes a readable unit`() {
        assertEquals(
            "42 %",
            phoneReadingLabel(PhoneSensor.BATTERY_LEVEL, HealthReading(numberValue = 42.0)),
        )
        assertEquals(
            "Да",
            phoneReadingLabel(PhoneSensor.IS_CHARGING, HealthReading(boolValue = true)),
        )
        assertNull(phoneReadingLabel(PhoneSensor.NEXT_ALARM, null))
    }
}
