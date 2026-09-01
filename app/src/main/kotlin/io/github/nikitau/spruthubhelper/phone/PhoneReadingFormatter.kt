package io.github.nikitau.spruthubhelper.phone

import io.github.nikitau.spruthubhelper.data.HealthValueKind
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.health.HealthReading
import kotlin.math.roundToLong

internal fun phoneReadingLabel(sensor: PhoneSensor, reading: HealthReading?): String? {
    if (reading == null) return null
    val value = when {
        reading.boolValue != null -> if (reading.boolValue) "Да" else "Нет"
        reading.stringValue != null -> reading.stringValue
        reading.numberValue != null && sensor.valueKind == HealthValueKind.INT ->
            reading.numberValue.roundToLong().toString()
        reading.numberValue != null -> reading.numberValue.toString().trimEnd('0').trimEnd('.')
        else -> return null
    }
    return if (reading.numberValue != null && sensor.unit.isNotBlank()) "$value ${sensor.unit}" else value
}
