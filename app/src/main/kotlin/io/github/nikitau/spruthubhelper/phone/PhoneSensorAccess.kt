package io.github.nikitau.spruthubhelper.phone

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.PhoneSensorAccess

internal fun requiredPhoneSensorAccesses(sensors: Set<PhoneSensor>): Set<PhoneSensorAccess> = sensors
    .map(PhoneSensor::access)
    .filterNot { it == PhoneSensorAccess.NONE }
    .toSet()

internal fun missingPhoneSensorAccesses(
    context: Context,
    sensors: Set<PhoneSensor>,
): Set<PhoneSensorAccess> = requiredPhoneSensorAccesses(sensors)
    .filterNotTo(mutableSetOf()) { access -> phoneSensorAccessGranted(context, access) }

internal fun phoneSensorAccessGranted(context: Context, access: PhoneSensorAccess): Boolean = when (access) {
    PhoneSensorAccess.NONE -> true
    PhoneSensorAccess.NOTIFICATION_POLICY -> context
        .getSystemService(NotificationManager::class.java)
        ?.isNotificationPolicyAccessGranted == true
}

internal fun unsupportedPhoneSensors(
    sensors: Set<PhoneSensor>,
    api: Int = Build.VERSION.SDK_INT,
): Set<PhoneSensor> = sensors.filterTo(mutableSetOf()) { sensor -> api < sensor.minimumApi }

internal fun phoneSensorReadinessError(
    missingAccesses: Set<PhoneSensorAccess>,
    unsupportedSensors: Set<PhoneSensor>,
): String? = when {
    unsupportedSensors.isNotEmpty() -> "Телефон не поддерживает: " +
        unsupportedSensors.joinToString { sensor -> "${sensor.title} (Android ${sensor.minimumApi}+)" }
    missingAccesses.isNotEmpty() -> "Сначала разрешите: " +
        missingAccesses.joinToString(transform = PhoneSensorAccess::title)
    else -> null
}
