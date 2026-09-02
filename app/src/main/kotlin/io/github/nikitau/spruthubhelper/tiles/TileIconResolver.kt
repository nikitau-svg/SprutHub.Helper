package io.github.nikitau.spruthubhelper.tiles

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.DeviceKind

object TileIconResolver {
    @DrawableRes
    fun resource(kind: DeviceKind): Int = when (kind) {
        DeviceKind.LIGHT -> R.drawable.ic_tile_light
        DeviceKind.THERMOSTAT -> R.drawable.ic_tile_thermostat
        DeviceKind.FAN -> R.drawable.ic_tile_fan
        DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER -> R.drawable.ic_tile_blinds
        DeviceKind.LOCK, DeviceKind.GARAGE, DeviceKind.SECURITY -> R.drawable.ic_tile_lock
        DeviceKind.VACUUM -> R.drawable.ic_tile_vacuum
        DeviceKind.TELEVISION -> R.drawable.ic_tile_tv
        DeviceKind.SCENE -> R.drawable.ic_tile_scene
        DeviceKind.SENSOR -> R.drawable.ic_tile_sensor
        DeviceKind.SWITCH, DeviceKind.OUTLET, DeviceKind.VALVE, DeviceKind.OTHER ->
            R.drawable.ic_tile
    }

    fun icon(context: Context, kind: DeviceKind): Icon = Icon.createWithResource(context, resource(kind))
}
