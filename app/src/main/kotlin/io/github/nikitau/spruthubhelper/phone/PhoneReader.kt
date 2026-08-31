package io.github.nikitau.spruthubhelper.phone

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Surface
import io.github.nikitau.spruthubhelper.BuildConfig
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.health.HealthReading
import java.net.Inet4Address
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt

class PhoneReader(private val context: Context) {
    fun read(selected: Set<PhoneSensor>): Map<String, HealthReading> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val memory = context.getSystemService(ActivityManager::class.java)?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }
        val rotation = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val linkProperties = activeNetwork?.let(connectivity::getLinkProperties)
        val storage = StatFs(Environment.getDataDirectory().absolutePath)

        return buildMap {
            PhoneSensor.entries.filter(selected::contains).forEach { sensor ->
                readingFor(
                    sensor = sensor,
                    battery = battery,
                    batteryManager = batteryManager,
                    power = power,
                    connectivity = connectivity,
                    capabilities = capabilities,
                    audio = audio,
                    notifications = notifications,
                    alarm = alarm,
                    memory = memory,
                    rotation = rotation,
                    localAddress = linkProperties?.linkAddresses
                        ?.map { it.address }
                        ?.filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        ?.sortedByDescending { it is Inet4Address }
                        ?.firstOrNull()
                        ?.hostAddress
                        ?.substringBefore('%'),
                    storage = storage,
                )?.let { reading -> put(sensor.name, reading) }
            }
        }
    }

    private fun readingFor(
        sensor: PhoneSensor,
        battery: Intent?,
        batteryManager: BatteryManager?,
        power: PowerManager?,
        connectivity: ConnectivityManager?,
        capabilities: NetworkCapabilities?,
        audio: AudioManager?,
        notifications: NotificationManager?,
        alarm: AlarmManager?,
        memory: ActivityManager.MemoryInfo?,
        rotation: Int?,
        localAddress: String?,
        storage: StatFs,
    ): HealthReading? = when (sensor) {
        PhoneSensor.BATTERY_LEVEL -> {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) HealthReading(numberValue = level * 100.0 / scale) else null
        }
        PhoneSensor.IS_CHARGING -> {
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            HealthReading(
                boolValue = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            )
        }
        PhoneSensor.BATTERY_STATE -> HealthReading(
            stringValue = batteryState(battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1),
        )
        PhoneSensor.CHARGER_TYPE -> HealthReading(
            stringValue = chargerType(battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0),
        )
        PhoneSensor.BATTERY_TEMPERATURE -> battery
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.let { HealthReading(numberValue = it / 10.0) }
        PhoneSensor.BATTERY_HEALTH -> HealthReading(
            stringValue = when (battery?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Хорошее"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Перегрев"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Неисправен"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Высокое напряжение"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Ошибка"
                BatteryManager.BATTERY_HEALTH_COLD -> "Переохлаждение"
                else -> "Неизвестно"
            },
        )
        PhoneSensor.BATTERY_VOLTAGE -> battery
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it >= 0 }
            ?.let { HealthReading(numberValue = it.toDouble()) }
        PhoneSensor.BATTERY_CURRENT -> batteryCurrentMicroamps(batteryManager)
            ?.let { HealthReading(numberValue = rounded(it / MICROAMPS_PER_MILLIAMP)) }
        PhoneSensor.BATTERY_POWER -> {
            val current = batteryCurrentMicroamps(batteryManager)
            val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it >= 0 }
            batteryPowerWatts(current, voltage)?.let { HealthReading(numberValue = rounded(it)) }
        }
        PhoneSensor.CHARGE_TIME_REMAINING -> batteryManager
            ?.computeChargeTimeRemaining()
            ?.takeIf { it >= 0L }
            ?.let { millis ->
                HealthReading(numberValue = ceil(millis / 60_000.0).toLong().toDouble())
            }
        PhoneSensor.BATTERY_CYCLE_COUNT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            battery?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)
                ?.takeIf { it >= 0 }
                ?.let { HealthReading(numberValue = it.toDouble()) }
        } else {
            null
        }
        PhoneSensor.POWER_SAVE_MODE -> HealthReading(boolValue = power?.isPowerSaveMode == true)
        PhoneSensor.CONNECTION_TYPE -> HealthReading(stringValue = connectionType(capabilities))
        PhoneSensor.NETWORK_METERED -> HealthReading(boolValue = connectivity?.isActiveNetworkMetered == true)
        PhoneSensor.NETWORK_VALIDATED -> HealthReading(
            boolValue = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
        PhoneSensor.LOCAL_IP -> localAddress?.let { HealthReading(stringValue = it) }
        PhoneSensor.SCREEN_BRIGHTNESS -> systemSetting(Settings.System.SCREEN_BRIGHTNESS)
            ?.let { HealthReading(numberValue = percent(it, MAX_SCREEN_BRIGHTNESS).toDouble()) }
        PhoneSensor.SCREEN_BRIGHTNESS_AUTO -> systemSetting(Settings.System.SCREEN_BRIGHTNESS_MODE)
            ?.let { mode ->
                HealthReading(boolValue = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            }
        PhoneSensor.SCREEN_TIMEOUT -> systemSetting(Settings.System.SCREEN_OFF_TIMEOUT)
            ?.takeIf { it >= 0 }
            ?.let { HealthReading(numberValue = (it / 1_000L).toDouble()) }
        PhoneSensor.SCREEN_ORIENTATION -> HealthReading(
            stringValue = screenOrientation(context.resources.configuration.orientation),
        )
        PhoneSensor.SCREEN_ROTATION -> rotation
            ?.let(::rotationDegrees)
            ?.let { HealthReading(numberValue = it.toDouble()) }
        PhoneSensor.RINGER_MODE -> audio?.ringerMode
            ?.let(::ringerMode)
            ?.let { HealthReading(stringValue = it) }
        PhoneSensor.DND_MODE -> notifications
            ?.takeIf { it.isNotificationPolicyAccessGranted }
            ?.currentInterruptionFilter
            ?.let(::interruptionFilter)
            ?.let { HealthReading(stringValue = it) }
        PhoneSensor.MUSIC_ACTIVE -> audio?.let { HealthReading(boolValue = it.isMusicActive) }
        PhoneSensor.MICROPHONE_MUTED -> audio?.let { HealthReading(boolValue = it.isMicrophoneMute) }
        PhoneSensor.MEDIA_VOLUME -> audio?.let {
            val maximum = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            HealthReading(numberValue = percent(it.getStreamVolume(AudioManager.STREAM_MUSIC), maximum).toDouble())
        }
        PhoneSensor.DEVICE_MODEL -> HealthReading(
            stringValue = "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}",
        )
        PhoneSensor.ANDROID_VERSION -> HealthReading(
            stringValue = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
        )
        PhoneSensor.SECURITY_PATCH -> Build.VERSION.SECURITY_PATCH
            .takeIf(String::isNotBlank)
            ?.let { HealthReading(stringValue = it) }
        PhoneSensor.APP_VERSION -> HealthReading(stringValue = BuildConfig.VERSION_NAME)
        PhoneSensor.SCREEN_INTERACTIVE -> HealthReading(boolValue = power?.isInteractive == true)
        PhoneSensor.DEVICE_IDLE -> HealthReading(boolValue = power?.isDeviceIdleMode == true)
        PhoneSensor.TIME_ZONE -> HealthReading(stringValue = ZoneId.systemDefault().id)
        PhoneSensor.NEXT_ALARM -> alarm?.nextAlarmClock?.triggerTime?.let { triggerTime ->
            HealthReading(
                stringValue = Instant.ofEpochMilli(triggerTime)
                    .atZone(ZoneId.systemDefault())
                    .toOffsetDateTime()
                    .toString(),
            )
        }
        PhoneSensor.UPTIME_HOURS -> HealthReading(
            numberValue = rounded(SystemClock.elapsedRealtime() / 3_600_000.0),
        )
        PhoneSensor.FREE_STORAGE_GB -> HealthReading(
            numberValue = rounded(storage.availableBytes / BYTES_PER_GIB),
        )
        PhoneSensor.TOTAL_STORAGE_GB -> HealthReading(
            numberValue = rounded(storage.totalBytes / BYTES_PER_GIB),
        )
        PhoneSensor.STORAGE_USED_PERCENT -> HealthReading(
            numberValue = storageUsedPercent(storage.availableBytes, storage.totalBytes).toDouble(),
        )
        PhoneSensor.AVAILABLE_MEMORY_MB -> memory?.let {
            HealthReading(numberValue = (it.availMem / BYTES_PER_MEBIBYTE).toDouble())
        }
        PhoneSensor.TOTAL_MEMORY_MB -> memory?.let {
            HealthReading(numberValue = (it.totalMem / BYTES_PER_MEBIBYTE).toDouble())
        }
        PhoneSensor.LOW_MEMORY -> memory?.let { HealthReading(boolValue = it.lowMemory) }
        PhoneSensor.SYNC_HEARTBEAT -> HealthReading(numberValue = heartbeatMinute().toDouble())
        PhoneSensor.LAST_SYNC -> HealthReading(stringValue = OffsetDateTime.now().toString())
    }

    private fun connectionType(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "Нет сети"
        return buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Мобильная сеть")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
        }.joinToString(" · ").ifBlank { "Другая сеть" }
    }

    private fun systemSetting(name: String): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, name)
    }.getOrNull()

    private fun batteryCurrentMicroamps(manager: BatteryManager?): Double? = manager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeUnless { it == Int.MIN_VALUE }
        ?.toDouble()

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private companion object {
        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
        const val BYTES_PER_MEBIBYTE = 1024L * 1024L
        const val MICROAMPS_PER_MILLIAMP = 1_000.0
        const val MAX_SCREEN_BRIGHTNESS = 255
    }
}

internal fun heartbeatMinute(epochMs: Long = System.currentTimeMillis()): Long =
    TimeUnit.MILLISECONDS.toMinutes(epochMs)

internal fun batteryState(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "Заряжается"
    BatteryManager.BATTERY_STATUS_FULL -> "Полностью заряжен"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Разряжается"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Не заряжается"
    else -> "Неизвестно"
}

internal fun chargerType(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "Сеть"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Беспроводная"
    BatteryManager.BATTERY_PLUGGED_DOCK -> "Док-станция"
    else -> "Не подключена"
}

internal fun batteryPowerWatts(currentMicroamps: Double?, voltageMillivolts: Int?): Double? {
    if (currentMicroamps == null || voltageMillivolts == null || voltageMillivolts < 0) return null
    return currentMicroamps * voltageMillivolts / 1_000_000_000.0
}

internal fun percent(value: Int, maximum: Int): Int = if (maximum <= 0) {
    0
} else {
    (value.coerceIn(0, maximum) * 100.0 / maximum).roundToInt()
}

internal fun screenOrientation(orientation: Int): String = when (orientation) {
    Configuration.ORIENTATION_PORTRAIT -> "Книжная"
    Configuration.ORIENTATION_LANDSCAPE -> "Альбомная"
    else -> "Не определена"
}

internal fun rotationDegrees(rotation: Int): Int = when (rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}

internal fun ringerMode(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_NORMAL -> "Звук"
    AudioManager.RINGER_MODE_VIBRATE -> "Вибрация"
    AudioManager.RINGER_MODE_SILENT -> "Без звука"
    else -> "Неизвестно"
}

internal fun interruptionFilter(filter: Int): String = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> "Выключен"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Только важные"
    NotificationManager.INTERRUPTION_FILTER_NONE -> "Полная тишина"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "Только будильники"
    else -> "Неизвестно"
}

internal fun storageUsedPercent(availableBytes: Long, totalBytes: Long): Int = if (totalBytes <= 0L) {
    0
} else {
    (((totalBytes - availableBytes).coerceIn(0L, totalBytes)) * 100.0 / totalBytes).roundToInt()
}
