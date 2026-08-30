package io.github.nikitau.spruthubhelper.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import io.github.nikitau.spruthubhelper.BuildConfig
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.health.HealthReading
import java.net.Inet4Address
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.round

class PhoneReader(private val context: Context) {
    fun read(selected: Set<PhoneSensor>): Map<String, HealthReading> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = context.getSystemService(PowerManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val linkProperties = activeNetwork?.let(connectivity::getLinkProperties)
        val storage = StatFs(Environment.getDataDirectory().absolutePath)

        return buildMap {
            PhoneSensor.entries.filter(selected::contains).forEach { sensor ->
                readingFor(
                    sensor = sensor,
                    battery = battery,
                    power = power,
                    connectivity = connectivity,
                    capabilities = capabilities,
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
        power: PowerManager?,
        connectivity: ConnectivityManager?,
        capabilities: NetworkCapabilities?,
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
        PhoneSensor.CHARGER_TYPE -> HealthReading(
            stringValue = when (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Сеть"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Беспроводная"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "Док-станция"
                else -> "Не подключена"
            },
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
        PhoneSensor.POWER_SAVE_MODE -> HealthReading(boolValue = power?.isPowerSaveMode == true)
        PhoneSensor.CONNECTION_TYPE -> HealthReading(stringValue = connectionType(capabilities))
        PhoneSensor.NETWORK_METERED -> HealthReading(boolValue = connectivity?.isActiveNetworkMetered == true)
        PhoneSensor.NETWORK_VALIDATED -> HealthReading(
            boolValue = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
        PhoneSensor.LOCAL_IP -> localAddress?.let { HealthReading(stringValue = it) }
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
        PhoneSensor.UPTIME_HOURS -> HealthReading(
            numberValue = rounded(SystemClock.elapsedRealtime() / 3_600_000.0),
        )
        PhoneSensor.FREE_STORAGE_GB -> HealthReading(
            numberValue = rounded(storage.availableBytes / BYTES_PER_GIB),
        )
        PhoneSensor.TOTAL_STORAGE_GB -> HealthReading(
            numberValue = rounded(storage.totalBytes / BYTES_PER_GIB),
        )
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

    private fun rounded(value: Double): Double = round(value * 100.0) / 100.0

    private companion object {
        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    }
}
