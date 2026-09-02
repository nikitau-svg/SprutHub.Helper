package io.github.nikitau.spruthubhelper.tiles

import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.icons.DefaultServiceIcon
import io.github.nikitau.spruthubhelper.icons.DefaultServiceIconResolver

object TileIconResolver {
    @DrawableRes
    fun resource(control: SprutControl): Int = resource(DefaultServiceIconResolver.resolve(control))

    @DrawableRes
    fun resource(card: ServiceControlCard): Int = resource(DefaultServiceIconResolver.resolve(card))

    @DrawableRes
    fun resource(kind: DeviceKind): Int = resource(
        DefaultServiceIconResolver.resolve(kind = kind, sourceType = "", descriptor = ""),
    )

    @DrawableRes
    fun resource(icon: DefaultServiceIcon): Int = when (icon) {
        DefaultServiceIcon.LIGHT -> R.drawable.ic_device_light
        DefaultServiceIcon.SWITCH -> R.drawable.ic_device_switch
        DefaultServiceIcon.OUTLET -> R.drawable.ic_device_outlet
        DefaultServiceIcon.FAN -> R.drawable.ic_device_fan
        DefaultServiceIcon.AIR_PURIFIER -> R.drawable.ic_device_air_purifier
        DefaultServiceIcon.CURTAINS -> R.drawable.ic_device_curtains
        DefaultServiceIcon.BLINDS -> R.drawable.ic_device_blinds
        DefaultServiceIcon.SHUTTER -> R.drawable.ic_device_shutter
        DefaultServiceIcon.DOOR -> R.drawable.ic_device_door
        DefaultServiceIcon.LOCK -> R.drawable.ic_device_lock
        DefaultServiceIcon.THERMOSTAT -> R.drawable.ic_device_thermostat
        DefaultServiceIcon.GARAGE -> R.drawable.ic_device_garage
        DefaultServiceIcon.VALVE -> R.drawable.ic_device_valve
        DefaultServiceIcon.FAUCET -> R.drawable.ic_device_faucet
        DefaultServiceIcon.IRRIGATION -> R.drawable.ic_device_irrigation
        DefaultServiceIcon.SECURITY -> R.drawable.ic_device_security
        DefaultServiceIcon.VACUUM -> R.drawable.ic_device_vacuum
        DefaultServiceIcon.TELEVISION -> R.drawable.ic_device_television
        DefaultServiceIcon.SPEAKER -> R.drawable.ic_device_speaker
        DefaultServiceIcon.SCENE -> R.drawable.ic_device_scene
        DefaultServiceIcon.AIR_QUALITY -> R.drawable.ic_device_air_quality
        DefaultServiceIcon.CO2 -> R.drawable.ic_device_co2
        DefaultServiceIcon.GAS -> R.drawable.ic_device_gas
        DefaultServiceIcon.TEMPERATURE -> R.drawable.ic_device_temperature
        DefaultServiceIcon.HUMIDITY -> R.drawable.ic_device_humidity
        DefaultServiceIcon.BATTERY -> R.drawable.ic_device_battery
        DefaultServiceIcon.CHARGING -> R.drawable.ic_device_charging
        DefaultServiceIcon.CONTACT -> R.drawable.ic_device_contact
        DefaultServiceIcon.MOTION -> R.drawable.ic_device_motion
        DefaultServiceIcon.LEAK -> R.drawable.ic_device_leak
        DefaultServiceIcon.SMOKE -> R.drawable.ic_device_smoke
        DefaultServiceIcon.ILLUMINANCE -> R.drawable.ic_device_illuminance
        DefaultServiceIcon.PRESSURE -> R.drawable.ic_device_pressure
        DefaultServiceIcon.NOISE -> R.drawable.ic_device_noise
        DefaultServiceIcon.DISTANCE -> R.drawable.ic_device_distance
        DefaultServiceIcon.ELECTRICITY -> R.drawable.ic_device_electricity
        DefaultServiceIcon.UV -> R.drawable.ic_device_uv
        DefaultServiceIcon.FILTER -> R.drawable.ic_device_filter
        DefaultServiceIcon.PET -> R.drawable.ic_device_pet
        DefaultServiceIcon.MASSAGE -> R.drawable.ic_device_massage
        DefaultServiceIcon.PHONE -> R.drawable.ic_device_phone
        DefaultServiceIcon.HEART -> R.drawable.ic_device_heart
        DefaultServiceIcon.STEPS -> R.drawable.ic_device_steps
        DefaultServiceIcon.SLEEP -> R.drawable.ic_device_sleep
        DefaultServiceIcon.WEIGHT -> R.drawable.ic_device_weight
        DefaultServiceIcon.BLOOD_PRESSURE -> R.drawable.ic_device_blood_pressure
        DefaultServiceIcon.OXYGEN -> R.drawable.ic_device_oxygen
        DefaultServiceIcon.CALORIES -> R.drawable.ic_device_calories
        DefaultServiceIcon.RESPIRATORY -> R.drawable.ic_device_respiratory
        DefaultServiceIcon.SYNC -> R.drawable.ic_device_sync
        DefaultServiceIcon.NETWORK -> R.drawable.ic_device_network
        DefaultServiceIcon.AUDIO -> R.drawable.ic_device_audio
        DefaultServiceIcon.DISPLAY -> R.drawable.ic_device_display
        DefaultServiceIcon.INFO -> R.drawable.ic_device_info
        DefaultServiceIcon.SENSOR -> R.drawable.ic_device_sensor
        DefaultServiceIcon.OTHER -> R.drawable.ic_device_other
    }

    fun icon(context: Context, kind: DeviceKind): Icon = Icon.createWithResource(context, resource(kind))

    fun icon(context: Context, control: SprutControl): Icon = Icon.createWithResource(context, resource(control))

    fun icon(context: Context, card: ServiceControlCard): Icon = Icon.createWithResource(context, resource(card))
}
