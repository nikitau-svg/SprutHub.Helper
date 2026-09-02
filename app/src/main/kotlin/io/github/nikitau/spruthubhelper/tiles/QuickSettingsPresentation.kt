package io.github.nikitau.spruthubhelper.tiles

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.ControlSurfacePresentation
import io.github.nikitau.spruthubhelper.data.CharacteristicDisplayValue
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.TileLabelStyle
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
import io.github.nikitau.spruthubhelper.data.surfaceValue

internal enum class QuickSettingsVisualState {
    ACTIVE,
    INACTIVE,
    UNAVAILABLE,
}

internal data class QuickSettingsPresentation(
    val label: String,
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
    headline: CharacteristicDisplayValue? = null,
    labelStyle: TileLabelStyle = TileLabelStyle.ACCESSORY,
): QuickSettingsPresentation {
    val unavailableReason = when {
        error != null -> error.take(30)
        surface.pending -> surface.statusLabel ?: "Обновление…"
        !surface.stateIsAuthoritative -> surface.statusLabel ?: "Нет связи"
        else -> null
    }
    val value = (headline?.value ?: control.surfaceValue())
        .takeUnless { it.isBlank() || it == "—" }
        ?: "Нет данных"
    val describedValue = headline?.label
        ?.takeUnless { it in GENERIC_SENSOR_LABELS }
        ?.let { "$it: $value" }
        ?: value
    val status = unavailableReason ?: if (control.behavior == ControlBehavior.SENSOR) value else describedValue
    val identity = tileIdentityLabel(control, labelStyle)
    val label = if (control.behavior == ControlBehavior.SENSOR) {
        val subject = headline?.label
            ?.takeUnless { it in GENERIC_SENSOR_LABELS }
            ?: sensorSubject(control)
        // Some OEM SystemUI implementations hide Tile.subtitle even in the
        // expanded shade. Keep a sensor's state visible in the primary label
        // while leaving actionable tile names stable.
        "$status · $subject"
    } else {
        identity
    }
    val subtitle = if (control.behavior == ControlBehavior.SENSOR) identity else status
    val visualState = when {
        unavailableReason != null -> QuickSettingsVisualState.UNAVAILABLE
        control.behavior == ControlBehavior.TOGGLE ||
            control.behavior == ControlBehavior.TOGGLE_RANGE -> {
            if (surface.active) QuickSettingsVisualState.ACTIVE else QuickSettingsVisualState.INACTIVE
        }
        else -> QuickSettingsVisualState.INACTIVE
    }
    return QuickSettingsPresentation(
        label = label,
        subtitle = subtitle,
        stateDescription = status,
        contentDescription = listOf(
            identity,
            control.title,
            control.subtitle.ifBlank { control.room },
            headline?.label,
            status,
            value.takeIf { unavailableReason != null }?.let { "Последнее значение: $it" },
        ).filterNotNull().filter(String::isNotBlank).distinct().joinToString(", "),
        visualState = visualState,
    )
}

/**
 * Android may hide a tile subtitle, so the actionable tile's primary label
 * must carry enough context by itself. This affects presentation only; the
 * command still uses the stable control id stored in [io.github.nikitau.spruthubhelper.data.TileAssignment].
 */
internal fun tileIdentityLabel(control: SprutControl, style: TileLabelStyle): String {
    val accessory = control.title.trim()
    if (style == TileLabelStyle.ACCESSORY) return accessory.ifBlank { "Устройство" }

    val service = buildServiceControlCards(listOf(control))
        .singleOrNull()
        ?.displayServiceName()
        .orEmpty()
        .trim()
    val room = control.room.trim().takeUnless { it.isBlank() || it == "Без комнаты" }.orEmpty()
    return listOf(room, service)
        .filter(String::isNotBlank)
        .distinctBy { value -> value.lowercase().filter(Char::isLetterOrDigit) }
        .joinToString(" · ")
        .ifBlank { accessory.ifBlank { "Устройство" } }
}

private fun sensorSubject(control: SprutControl): String {
    val card = buildServiceControlCards(listOf(control)).single()
    val characteristic = card.characteristicValues().singleOrNull()?.label
    return characteristic
        ?.takeUnless { it in GENERIC_SENSOR_LABELS }
        ?: card.displayServiceName()
}

private val GENERIC_SENSOR_LABELS = setOf("Сейчас", "Состояние", "Параметр")
