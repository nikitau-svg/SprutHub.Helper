package io.github.nikitau.spruthubhelper.widget

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.SprutControl

enum class WidgetPrimaryAction {
    TOGGLE,
    EXECUTE,
    OPEN_APP,
}

data class WidgetActionDecision(
    val action: WidgetPrimaryAction,
    val booleanValue: Boolean? = null,
)

object WidgetActionResolver {
    fun resolve(control: SprutControl): WidgetActionDecision = when {
        !control.writable -> WidgetActionDecision(WidgetPrimaryAction.OPEN_APP)
        control.behavior == ControlBehavior.TOGGLE || control.behavior == ControlBehavior.TOGGLE_RANGE ->
            WidgetActionDecision(WidgetPrimaryAction.TOGGLE, !control.value.asBoolean())
        control.behavior == ControlBehavior.BUTTON -> WidgetActionDecision(WidgetPrimaryAction.EXECUTE)
        else -> WidgetActionDecision(WidgetPrimaryAction.OPEN_APP)
    }
}
