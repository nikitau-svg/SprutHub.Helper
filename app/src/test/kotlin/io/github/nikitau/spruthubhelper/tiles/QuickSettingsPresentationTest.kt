package io.github.nikitau.spruthubhelper.tiles

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.ControlSurfacePresentation
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSettingsPresentationTest {
    @Test
    fun `read only sensor stays available and exposes its current value`() {
        val result = quickSettingsPresentation(
            control = control(
                behavior = ControlBehavior.SENSOR,
                value = SprutValue(numberValue = 1.0),
            ),
            surface = live(active = false),
        )

        assertEquals("Качество воздуха · Отличное", result.label)
        assertEquals("Отличное", result.subtitle)
        assertEquals("Отличное", result.stateDescription)
        assertEquals(QuickSettingsVisualState.INACTIVE, result.visualState)
        assertEquals("Qingping Air Monitor Lite, Air Quality, Отличное", result.contentDescription)
    }

    @Test
    fun `switch value is explicit in addition to active color`() {
        val result = quickSettingsPresentation(
            control = control(
                behavior = ControlBehavior.TOGGLE,
                value = SprutValue(boolValue = false),
            ),
            surface = live(active = false),
        )

        assertEquals("Qingping Air Monitor Lite", result.label)
        assertEquals("Выключено", result.subtitle)
        assertEquals(QuickSettingsVisualState.INACTIVE, result.visualState)
    }

    @Test
    fun `connection problem overrides cached value`() {
        val result = quickSettingsPresentation(
            control = control(
                behavior = ControlBehavior.SENSOR,
                value = SprutValue(numberValue = 1.0),
            ),
            surface = ControlSurfacePresentation(
                stateIsAuthoritative = false,
                pending = false,
                active = false,
                statusLabel = "Нет связи",
            ),
        )

        assertEquals("Качество воздуха · Нет связи", result.label)
        assertEquals("Нет связи", result.subtitle)
        assertEquals(QuickSettingsVisualState.UNAVAILABLE, result.visualState)
    }

    private fun control(behavior: ControlBehavior, value: SprutValue) = SprutControl(
        id = "1:1:1",
        accessoryId = "1",
        serviceId = "1",
        characteristicId = "1",
        title = "Qingping Air Monitor Lite",
        subtitle = "Air Quality",
        room = "Спальня Никита",
        kind = DeviceKind.SENSOR,
        behavior = behavior,
        value = value,
        characteristicType = "AirQuality",
    )

    private fun live(active: Boolean) = ControlSurfacePresentation(
        stateIsAuthoritative = true,
        pending = false,
        active = active,
    )
}
