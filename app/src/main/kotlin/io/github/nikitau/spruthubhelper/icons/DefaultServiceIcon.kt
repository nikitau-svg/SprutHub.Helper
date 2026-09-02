package io.github.nikitau.spruthubhelper.icons

import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.ServiceControlCard
import io.github.nikitau.spruthubhelper.data.SprutControl
import java.util.Locale

/** Semantic built-in icon independent from control behavior and user-editable names. */
enum class DefaultServiceIcon {
    LIGHT,
    SWITCH,
    OUTLET,
    FAN,
    AIR_PURIFIER,
    CURTAINS,
    BLINDS,
    SHUTTER,
    DOOR,
    LOCK,
    THERMOSTAT,
    GARAGE,
    VALVE,
    FAUCET,
    IRRIGATION,
    SECURITY,
    VACUUM,
    TELEVISION,
    SPEAKER,
    SCENE,
    AIR_QUALITY,
    CO2,
    GAS,
    TEMPERATURE,
    HUMIDITY,
    BATTERY,
    CHARGING,
    CONTACT,
    MOTION,
    LEAK,
    SMOKE,
    ILLUMINANCE,
    PRESSURE,
    NOISE,
    DISTANCE,
    ELECTRICITY,
    UV,
    FILTER,
    PET,
    MASSAGE,
    PHONE,
    HEART,
    STEPS,
    SLEEP,
    WEIGHT,
    BLOOD_PRESSURE,
    OXYGEN,
    CALORIES,
    RESPIRATORY,
    SYNC,
    NETWORK,
    AUDIO,
    DISPLAY,
    INFO,
    SENSOR,
    OTHER,
}

/**
 * Resolves a visual role without changing the command address or [DeviceKind].
 *
 * SprutHub's standard service type is the strongest signal. Universal custom
 * services such as C_Option then use their service/characteristic metadata so
 * phone and Health Connect fields still receive useful defaults.
 */
object DefaultServiceIconResolver {
    fun resolve(control: SprutControl): DefaultServiceIcon = resolve(
        kind = control.kind,
        sourceType = control.sourceType,
        descriptor = listOf(
            control.serviceName,
            control.characteristicType,
            control.characteristicName,
            control.subtitle,
        ).joinToString(" "),
    )

    fun resolve(card: ServiceControlCard): DefaultServiceIcon = resolve(
        kind = card.kind,
        sourceType = card.serviceType,
        descriptor = buildString {
            append(card.serviceName)
            append(' ')
            append(card.primaryControl.characteristicType)
            append(' ')
            append(card.primaryControl.characteristicName)
            append(' ')
            append(card.primaryControl.subtitle)
        },
    )

    internal fun resolve(
        kind: DeviceKind,
        sourceType: String,
        descriptor: String,
    ): DefaultServiceIcon {
        iconForServiceType(normalizeType(sourceType))?.let { return it }
        iconForDescriptor(normalizeText(descriptor))?.let { return it }
        return iconForKind(kind)
    }

    private fun iconForServiceType(type: String): DefaultServiceIcon? = when (type) {
        "lightbulb" -> DefaultServiceIcon.LIGHT
        "switch", "statelessprogrammableswitch", "targetcontrol", "doorbell", "option" -> null
        "outlet" -> DefaultServiceIcon.OUTLET
        "fan", "fanbasic" -> DefaultServiceIcon.FAN
        "airpurifier" -> DefaultServiceIcon.AIR_PURIFIER
        "windowcovering", "slat" -> DefaultServiceIcon.BLINDS
        "window", "door" -> DefaultServiceIcon.DOOR
        "lockmechanism", "lockmanagement", "accesscontrol" -> DefaultServiceIcon.LOCK
        "thermostat", "heatercooler", "temperaturecontrol" -> DefaultServiceIcon.THERMOSTAT
        "humidifierdehumidifier" -> DefaultServiceIcon.HUMIDITY
        "garagedooropener" -> DefaultServiceIcon.GARAGE
        "valve" -> DefaultServiceIcon.VALVE
        "faucet" -> DefaultServiceIcon.FAUCET
        "irrigationsystem" -> DefaultServiceIcon.IRRIGATION
        "securitysystem" -> DefaultServiceIcon.SECURITY
        "vacuumcleaner" -> DefaultServiceIcon.VACUUM
        "television", "inputsource" -> DefaultServiceIcon.TELEVISION
        "speaker", "televisionspeaker", "microphone" -> DefaultServiceIcon.SPEAKER
        "scenario" -> DefaultServiceIcon.SCENE
        "airqualitysensor" -> DefaultServiceIcon.AIR_QUALITY
        "carbondioxidesensor" -> DefaultServiceIcon.CO2
        "carbonmonoxidesensor", "gassensor", "gasmeter" -> DefaultServiceIcon.GAS
        "temperaturesensor", "heatmeter" -> DefaultServiceIcon.TEMPERATURE
        "humiditysensor" -> DefaultServiceIcon.HUMIDITY
        "batteryservice" -> DefaultServiceIcon.BATTERY
        "contactsensor" -> DefaultServiceIcon.CONTACT
        "motionsensor", "occupancysensor" -> DefaultServiceIcon.MOTION
        "leaksensor", "watermeter" -> DefaultServiceIcon.LEAK
        "smokesensor" -> DefaultServiceIcon.SMOKE
        "lightsensor" -> DefaultServiceIcon.ILLUMINANCE
        "atmosphericpressuresensor" -> DefaultServiceIcon.PRESSURE
        "noisesensor" -> DefaultServiceIcon.NOISE
        "distancesensor", "tiltangle", "anglemeter", "voltanglemeter", "phaseanglemeter" ->
            DefaultServiceIcon.DISTANCE
        "voltmeter",
        "amperemeter",
        "wattmeter",
        "voltamperemeter",
        "kilowatthourmeter",
        "kilovoltamperehourmeter",
        "voltpeakmeter",
        "voltamperereactivemeter",
        "kilovoltamperereactivehourmeter",
        "powerfactormeter",
        "frequencymeter",
        "amperepeakmeter",
        "powermanagement" -> DefaultServiceIcon.ELECTRICITY
        "ultravioletsensor" -> DefaultServiceIcon.UV
        "filtermaintenance" -> DefaultServiceIcon.FILTER
        "petfeeder" -> DefaultServiceIcon.PET
        "massage" -> DefaultServiceIcon.MASSAGE
        "pulsemeter" -> DefaultServiceIcon.HEART
        "repeater", "transceiver", "wifirouter", "wifisatellite" -> DefaultServiceIcon.NETWORK
        "accessoryinformation", "accessoryextinfo", "genericservice" -> DefaultServiceIcon.INFO
        else -> null
    }

    private fun iconForDescriptor(text: String): DefaultServiceIcon? = when {
        text.containsAny("synchron", "syncheartbeat", "heartbeat", "синхрон", "пульссинхронизац") ->
            DefaultServiceIcon.SYNC
        text.containsAny("steps", "stepcount", "шагисегодня", "шагов") -> DefaultServiceIcon.STEPS
        text.containsAny("sleep", "последнийсон", "сончас") -> DefaultServiceIcon.SLEEP
        text.containsAny("weight", "bodymass", "вескг", "вес") -> DefaultServiceIcon.WEIGHT
        text.containsAny("spo2", "oxygensaturation", "кислородвкров") -> DefaultServiceIcon.OXYGEN
        text.containsAny(
            "bloodpressure",
            "systolic",
            "diastolic",
            "давлениеверх",
            "давлениениж",
        ) -> DefaultServiceIcon.BLOOD_PRESSURE
        text.containsAny("calorie", "activeenergy", "активныекалор", "ккал") -> DefaultServiceIcon.CALORIES
        text.containsAny("respiratory", "breathing", "частотадыхан", "вдохмин") ->
            DefaultServiceIcon.RESPIRATORY
        text.containsAny("heartrate", "restingheart", "hrv", "pulse", "пульс", "вариабельность") ->
            DefaultServiceIcon.HEART
        text.containsAny("ischarging", "chargingstate", "charger", "подключеназаряд", "типзаряд") ->
            DefaultServiceIcon.CHARGING
        text.containsAny("battery", "аккумулятор", "уровеньзаряд", "зарядаккумулятора") ->
            DefaultServiceIcon.BATTERY
        text.containsAny("carbondioxide", "co2", "углекисл") -> DefaultServiceIcon.CO2
        text.containsAny("carbonmonoxide", "угар", "gassensor", "газ") -> DefaultServiceIcon.GAS
        text.containsAny("airquality", "particulate", "pm10", "pm25", "качествовоздуха") ->
            DefaultServiceIcon.AIR_QUALITY
        text.containsAny("humidity", "влажност") -> DefaultServiceIcon.HUMIDITY
        text.containsAny("bodytemperature", "temperature", "температур") -> DefaultServiceIcon.TEMPERATURE
        text.containsAny("atmosphericpressure", "barometer", "давлениеатмосфер") -> DefaultServiceIcon.PRESSURE
        text.containsAny("smoke", "дым") -> DefaultServiceIcon.SMOKE
        text.containsAny("leak", "waterdetected", "протеч", "утечкавод") -> DefaultServiceIcon.LEAK
        text.containsAny("contactsensor", "contactstate", "контакт", "открытиедвер") ->
            DefaultServiceIcon.CONTACT
        text.containsAny("motion", "occupancy", "движен", "присутств") -> DefaultServiceIcon.MOTION
        text.containsAny("ambientlight", "illuminance", "lux", "освещенн") -> DefaultServiceIcon.ILLUMINANCE
        text.containsAny("noise", "soundlevel", "уровеньшума", "шум") -> DefaultServiceIcon.NOISE
        text.containsAny("distance", "дистанц", "расстояни") -> DefaultServiceIcon.DISTANCE
        text.containsAny("ultraviolet", "uvindex", "ультрафиолет") -> DefaultServiceIcon.UV
        text.containsAny("voltage", "current", "watt", "powerfactor", "energy", "напряжен", "мощност", "энерги") ->
            DefaultServiceIcon.ELECTRICITY
        text.containsAny("internet", "network", "wifi", "connectiontype", "сеть", "интернет", "подключени") ->
            DefaultServiceIcon.NETWORK
        text.containsAny("volume", "audio", "ringer", "donotdisturb", "звук", "громкост", "беспокоить") ->
            DefaultServiceIcon.AUDIO
        text.containsAny("display", "screen", "brightness", "orientation", "экран", "яркост", "ориентац") ->
            DefaultServiceIcon.DISPLAY
        text.containsAny(
            "phonemodel",
            "androidversion",
            "smartphone",
            "телефонмодель",
            "модельтелефон",
            "телефонandroid",
            "версияandroid",
        ) ->
            DefaultServiceIcon.PHONE
        text.containsAny("filter", "фильтр") -> DefaultServiceIcon.FILTER
        text.containsAny("petfeeder", "кормуш") -> DefaultServiceIcon.PET
        text.containsAny("massage", "массаж") -> DefaultServiceIcon.MASSAGE
        else -> null
    }

    private fun iconForKind(kind: DeviceKind): DefaultServiceIcon = when (kind) {
        DeviceKind.LIGHT -> DefaultServiceIcon.LIGHT
        DeviceKind.SWITCH -> DefaultServiceIcon.SWITCH
        DeviceKind.OUTLET -> DefaultServiceIcon.OUTLET
        DeviceKind.FAN -> DefaultServiceIcon.FAN
        DeviceKind.CURTAIN -> DefaultServiceIcon.CURTAINS
        DeviceKind.BLINDS -> DefaultServiceIcon.BLINDS
        DeviceKind.SHUTTER -> DefaultServiceIcon.SHUTTER
        DeviceKind.LOCK -> DefaultServiceIcon.LOCK
        DeviceKind.THERMOSTAT -> DefaultServiceIcon.THERMOSTAT
        DeviceKind.GARAGE -> DefaultServiceIcon.GARAGE
        DeviceKind.VALVE -> DefaultServiceIcon.VALVE
        DeviceKind.SECURITY -> DefaultServiceIcon.SECURITY
        DeviceKind.VACUUM -> DefaultServiceIcon.VACUUM
        DeviceKind.TELEVISION -> DefaultServiceIcon.TELEVISION
        DeviceKind.SCENE -> DefaultServiceIcon.SCENE
        DeviceKind.SENSOR -> DefaultServiceIcon.SENSOR
        DeviceKind.OTHER -> DefaultServiceIcon.OTHER
    }

    private fun normalizeType(value: String): String = value
        .trim()
        .replace(Regex("^(?:(?:HS|HC|S|C)[._:-])+", RegexOption.IGNORE_CASE), "")
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun normalizeText(value: String): String = value
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

    private fun String.containsAny(vararg markers: String): Boolean = markers.any(::contains)
}
