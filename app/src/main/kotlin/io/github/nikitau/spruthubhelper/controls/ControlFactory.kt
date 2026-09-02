package io.github.nikitau.spruthubhelper.controls

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import io.github.nikitau.spruthubhelper.data.CatalogFreshness
import io.github.nikitau.spruthubhelper.data.CatalogFreshnessPhase
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.ServicePresentationPreference
import io.github.nikitau.spruthubhelper.data.presentationFor
import io.github.nikitau.spruthubhelper.icons.CustomIconManager
import io.github.nikitau.spruthubhelper.ui.MainActivity

object ControlFactory {
    fun stateless(context: Context, item: SprutControl, card: ServiceControlCard? = null): Control {
        val builder = Control.StatelessBuilder(item.id, appIntent(context, item))
            .setTitle(card?.title ?: item.title)
            .setSubtitle(card?.displayServiceName() ?: item.subtitle)
            .setStructure("SprutHub")
            .setZone(item.room)
            .setDeviceType(item.deviceType())
        (card?.let { CustomIconManager(context).loadIcon(it.id) }
            ?: CustomIconManager(context).loadIcon(item.id))?.let(builder::setCustomIcon)
        return builder.build()
    }

    fun stateful(
        context: Context,
        item: SprutControl,
        freshness: CatalogFreshness,
        card: ServiceControlCard? = null,
        preference: ServicePresentationPreference? = null,
    ): Control {
        val presentation = freshness.presentationFor(item)
        val headlineValue = card?.headlineDisplayValue(preference)
            ?.let { "${it.label}: ${it.value}" }
            ?: item.displayValue
        val pending = presentation.pending
        val authoritative = presentation.stateIsAuthoritative
        val status = when {
            authoritative -> Control.STATUS_OK
            pending || freshness.phase == CatalogFreshnessPhase.REFRESHING -> Control.STATUS_UNKNOWN
            else -> Control.STATUS_ERROR
        }
        val statusText = when {
            pending -> "Подтверждаем…"
            authoritative && presentation.statusLabel != null ->
                "${presentation.statusLabel} · $headlineValue"
            authoritative -> headlineValue
            item.behavior == ControlBehavior.BUTTON -> freshness.shortLabel
            else -> "${freshness.shortLabel} · последнее: $headlineValue"
        }
        val builder = Control.StatefulBuilder(item.id, appIntent(context, item))
            .setTitle(card?.title ?: item.title)
            .setSubtitle(card?.displayServiceName() ?: item.subtitle)
            .setStructure("SprutHub")
            .setZone(item.room)
            .setDeviceType(item.deviceType())
            .setStatus(status)
            .setStatusText(statusText)
            .setControlTemplate(if (authoritative) item.template() else StatelessTemplate("unavailable:${item.id}"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setAuthRequired(item.requiresAuthentication())
        }
        (card?.let { CustomIconManager(context).loadIcon(it.id) }
            ?: CustomIconManager(context).loadIcon(item.id))?.let(builder::setCustomIcon)
        return builder.build()
    }

    private fun SprutControl.template(): ControlTemplate = when (behavior) {
        ControlBehavior.TOGGLE -> ToggleTemplate(
            "toggle:$id",
            ControlButton(value.asBoolean(), if (value.asBoolean()) "Выключить" else "Включить"),
        )
        ControlBehavior.RANGE -> RangeTemplate(
            "range:$id",
            minimum.toFloat(),
            maximum.toFloat(),
            value.asDouble().coerceIn(minimum, maximum).toFloat(),
            step.coerceAtLeast(0.1).toFloat(),
            if (unit.isBlank()) "%.0f" else "%.0f $unit",
        )
        ControlBehavior.TOGGLE_RANGE -> ToggleRangeTemplate(
            "toggle-range:$id",
            ControlButton(value.asBoolean(), if (value.asBoolean()) "Выключить" else "Включить"),
            RangeTemplate(
                "range:$id",
                minimum.toFloat(),
                maximum.toFloat(),
                value.asDouble().coerceIn(minimum, maximum).toFloat(),
                step.coerceAtLeast(0.1).toFloat(),
                if (unit.isBlank()) "%.0f" else "%.0f $unit",
            ),
        )
        ControlBehavior.OPTIONS, ControlBehavior.BUTTON, ControlBehavior.SENSOR ->
            StatelessTemplate("command:$id")
    }

    private fun SprutControl.deviceType(): Int = when (kind) {
        DeviceKind.LIGHT -> DeviceTypes.TYPE_LIGHT
        DeviceKind.SWITCH -> DeviceTypes.TYPE_SWITCH
        DeviceKind.OUTLET -> DeviceTypes.TYPE_OUTLET
        DeviceKind.FAN -> DeviceTypes.TYPE_FAN
        DeviceKind.CURTAIN -> DeviceTypes.TYPE_CURTAIN
        DeviceKind.BLINDS -> DeviceTypes.TYPE_BLINDS
        DeviceKind.SHUTTER -> DeviceTypes.TYPE_SHUTTER
        DeviceKind.LOCK -> DeviceTypes.TYPE_LOCK
        DeviceKind.THERMOSTAT -> DeviceTypes.TYPE_THERMOSTAT
        DeviceKind.GARAGE -> DeviceTypes.TYPE_GARAGE
        DeviceKind.VALVE -> DeviceTypes.TYPE_VALVE
        DeviceKind.SECURITY -> DeviceTypes.TYPE_SECURITY_SYSTEM
        DeviceKind.VACUUM -> DeviceTypes.TYPE_VACUUM
        DeviceKind.TELEVISION -> DeviceTypes.TYPE_TV
        DeviceKind.SCENE -> DeviceTypes.TYPE_ROUTINE
        DeviceKind.SENSOR, DeviceKind.OTHER -> DeviceTypes.TYPE_UNKNOWN
    }

    private fun appIntent(context: Context, item: SprutControl): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CONTROL_ID, item.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Locks and perimeter controls are never considered safe lock-screen actions. */
internal fun SprutControl.requiresAuthentication(): Boolean = when (kind) {
    DeviceKind.LOCK,
    DeviceKind.SECURITY,
    DeviceKind.GARAGE -> true
    else -> false
}
