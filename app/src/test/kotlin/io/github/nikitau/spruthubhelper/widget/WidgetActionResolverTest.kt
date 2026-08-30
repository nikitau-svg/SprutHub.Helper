package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetActionResolverTest {
    @Test
    fun toggleInvertsCurrentValue() {
        val enabled = control(ControlBehavior.TOGGLE, SprutValue(boolValue = true))
        val disabled = control(ControlBehavior.TOGGLE_RANGE, SprutValue(boolValue = false))

        val offDecision = WidgetActionResolver.resolve(enabled)
        val onDecision = WidgetActionResolver.resolve(disabled)

        assertEquals(WidgetPrimaryAction.TOGGLE, offDecision.action)
        assertFalse(requireNotNull(offDecision.booleanValue))
        assertEquals(WidgetPrimaryAction.TOGGLE, onDecision.action)
        assertTrue(requireNotNull(onDecision.booleanValue))
    }

    @Test
    fun buttonExecutesAndSensorOpensApp() {
        assertEquals(
            WidgetPrimaryAction.EXECUTE,
            WidgetActionResolver.resolve(control(ControlBehavior.BUTTON)).action,
        )
        assertEquals(
            WidgetPrimaryAction.OPEN_APP,
            WidgetActionResolver.resolve(control(ControlBehavior.SENSOR, writable = false)).action,
        )
    }

    private fun control(
        behavior: ControlBehavior,
        value: SprutValue = SprutValue(),
        writable: Boolean = true,
    ) = SprutControl(
        id = "1:2:3",
        accessoryId = "1",
        serviceId = "2",
        characteristicId = "3",
        title = "Тест",
        behavior = behavior,
        value = value,
        writable = writable,
    )
}
