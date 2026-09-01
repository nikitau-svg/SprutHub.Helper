package io.github.nikitau.spruthubhelper.tiles

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.ControlSurfacePresentation
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.surfaceValue

internal enum class QuickSettingsVisualState {
    ACTIVE,
    INACTIVE,
    UNAVAILABLE,
}

internal data class QuickSettingsPresentation(
    val subtitle: String,
    val stateDescription: String,
    val contentDescription: String,
    val visualState: QuickSettingsVisualState,
)

/**
 * Keeps Quick Settings semantics independent from an OEM's visual treatment.
 * A read-only sensor is available and opens details; it is not a broken tile.
 */
internal fun quickSettingsPresentation(
    control: SprutControl,
    surface: ControlSurfacePresentation,
    error: String? = null,
): QuickSettingsPresentation {
    val unavailableReason = when {
        error != null -> error.take(30)
        surface.pending -> surface.statusLabel ?: "Обновление…"
        !surface.stateIsAuthoritative -> surface.statusLabel ?: "Нет связи"
        else -> null
    }
    val value = control.surfaceValue()
        .takeUnless { it.isBlank() || it == "—" }
        ?: "Нет данных"
    val subtitle = unavailableReason ?: value
    val visualState = when {
        unavailableReason != null -> QuickSettingsVisualState.UNAVAILABLE
        control.behavior == ControlBehavior.TOGGLE ||
            control.behavior == ControlBehavior.TOGGLE_RANGE -> {
            if (surface.active) QuickSettingsVisualState.ACTIVE else QuickSettingsVisualState.INACTIVE
        }
        else -> QuickSettingsVisualState.INACTIVE
    }
    return QuickSettingsPresentation(
        subtitle = subtitle,
        stateDescription = subtitle,
        contentDescription = listOf(
            control.title,
            control.subtitle.ifBlank { control.room },
            subtitle,
            value.takeIf { unavailableReason != null }?.let { "Последнее значение: $it" },
        ).filterNotNull().filter(String::isNotBlank).distinct().joinToString(", "),
        visualState = visualState,
    )
}
