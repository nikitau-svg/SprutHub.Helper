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

/**
 * A PendingIntent may outlive a previous widget layout. Only a control that is
 * still assigned to this widget is accepted; a stale or forged id falls back
 * to the primary item instead of reaching an arbitrary SprutHub control.
 */
internal fun resolveWidgetActionControlId(
    primaryControlId: String?,
    configuration: WidgetLayoutConfiguration?,
    requestedControlId: String?,
): String? {
    val primary = primaryControlId?.takeIf(String::isNotBlank) ?: return null
    val allowed = configuration?.items
        ?.mapTo(mutableSetOf(), WidgetItemConfiguration::controlId)
        .orEmpty() + primary
    return requestedControlId?.takeIf(allowed::contains) ?: primary
}
