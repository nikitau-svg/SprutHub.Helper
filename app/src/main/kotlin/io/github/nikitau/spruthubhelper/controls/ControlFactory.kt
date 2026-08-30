package io.github.nikitau.spruthubhelper.controls

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.templates.ControlButton
import android.service.controls.templates.ControlTemplate
import android.service.controls.templates.RangeTemplate
import android.service.controls.templates.StatelessTemplate
import android.service.controls.templates.ToggleRangeTemplate
import android.service.controls.templates.ToggleTemplate
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.ui.MainActivity

object ControlFactory {
    fun stateless(context: Context, item: SprutControl): Control = Control.StatelessBuilder(
        item.id,
        appIntent(context, item),
    )
        .setTitle(item.title)
        .setSubtitle(item.subtitle)
        .setStructure("SprutHub")
        .setZone(item.room)
        .setDeviceType(item.deviceType())
        .build()

    fun stateful(context: Context, item: SprutControl): Control = Control.StatefulBuilder(
        item.id,
        appIntent(context, item),
    )
        .setTitle(item.title)
        .setSubtitle(item.subtitle)
        .setStructure("SprutHub")
        .setZone(item.room)
        .setDeviceType(item.deviceType())
        .setStatus(Control.STATUS_OK)
        .setStatusText(item.displayValue)
        .setControlTemplate(item.template())
        .build()

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
        ControlBehavior.BUTTON, ControlBehavior.SENSOR -> StatelessTemplate("command:$id")
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
