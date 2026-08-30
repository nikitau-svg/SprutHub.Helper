package io.github.nikitau.spruthubhelper.health

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.sprut.VirtualHealthDeviceManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthReader(private val context: Context) {
    fun isAvailable(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun permissions(metrics: Set<HealthMetric>): Set<String> = metrics.mapTo(mutableSetOf(), ::permissionFor)

    suspend fun grantedPermissions(): Set<String> = if (isAvailable()) {
        client().permissionController.getGrantedPermissions()
    } else {
        emptySet()
    }

    suspend fun read(metrics: Set<HealthMetric>): Map<String, HealthReading> {
        check(isAvailable()) { "Health Connect недоступен" }
        val client = client()
        val granted = client.permissionController.getGrantedPermissions()
        val selected = metrics.filter { permissionFor(it) in granted }.toSet()
        val now = Instant.now()
        val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
        val recent = now.minus(Duration.ofDays(30))
        val readings = mutableMapOf<String, HealthReading>()

        suspend fun collect(metric: HealthMetric, block: suspend () -> Double?) {
            if (metric !in selected) return
            runCatching { block() }.getOrNull()?.let { readings[metric.name] = HealthReading(numberValue = it) }
        }

        collect(HealthMetric.STEPS) {
            client.aggregate(
                AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, now)),
            )[StepsRecord.COUNT_TOTAL]?.toDouble()
        }
        collect(HealthMetric.ACTIVE_CALORIES) {
            client.aggregate(
                AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), TimeRangeFilter.between(startOfDay, now)),
            )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        }
        collect(HealthMetric.DISTANCE) {
            client.aggregate(
                AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(startOfDay, now)),
            )[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
        }
        collect(HealthMetric.HEART_RATE) {
            client.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    TimeRangeFilter.between(now.minus(Duration.ofDays(2)), now),
                    ascendingOrder = false,
                    pageSize = 20,
                ),
            ).records.flatMap(HeartRateRecord::samples)
                .maxByOrNull(HeartRateRecord.Sample::time)
                ?.beatsPerMinute
                ?.toDouble()
        }
        collect(HealthMetric.RESTING_HEART_RATE) {
            latest<RestingHeartRateRecord>(client, recent, now)?.beatsPerMinute?.toDouble()
        }
        collect(HealthMetric.SLEEP) {
            latest<SleepSessionRecord>(client, now.minus(Duration.ofDays(7)), now)?.let {
                Duration.between(it.startTime, it.endTime).toMinutes() / 60.0
            }
        }
        collect(HealthMetric.WEIGHT) { latest<WeightRecord>(client, recent, now)?.weight?.inKilograms }
        collect(HealthMetric.OXYGEN_SATURATION) {
            latest<OxygenSaturationRecord>(client, recent, now)?.percentage?.value
        }
        if (HealthMetric.BLOOD_PRESSURE_SYSTOLIC in selected || HealthMetric.BLOOD_PRESSURE_DIASTOLIC in selected) {
            runCatching { latest<BloodPressureRecord>(client, recent, now) }.getOrNull()?.let { pressure ->
                if (HealthMetric.BLOOD_PRESSURE_SYSTOLIC in selected) {
                    readings[HealthMetric.BLOOD_PRESSURE_SYSTOLIC.name] =
                        HealthReading(numberValue = pressure.systolic.inMillimetersOfMercury)
                }
                if (HealthMetric.BLOOD_PRESSURE_DIASTOLIC in selected) {
                    readings[HealthMetric.BLOOD_PRESSURE_DIASTOLIC.name] =
                        HealthReading(numberValue = pressure.diastolic.inMillimetersOfMercury)
                }
            }
        }
        collect(HealthMetric.BODY_TEMPERATURE) {
            latest<BodyTemperatureRecord>(client, recent, now)?.temperature?.inCelsius
        }
        collect(HealthMetric.RESPIRATORY_RATE) { latest<RespiratoryRateRecord>(client, recent, now)?.rate }
        collect(HealthMetric.HRV) {
            latest<HeartRateVariabilityRmssdRecord>(client, recent, now)?.heartRateVariabilityMillis
        }

        readings += phoneReadings()
        return readings
    }

    private suspend inline fun <reified T : Record> latest(
        client: HealthConnectClient,
        from: Instant,
        to: Instant,
    ): T? = client.readRecords(
        ReadRecordsRequest(
            T::class,
            TimeRangeFilter.between(from, to),
            ascendingOrder = false,
            pageSize = 1,
        ),
    ).records.firstOrNull()

    private fun phoneReadings(): Map<String, HealthReading> {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return buildMap {
            if (level >= 0 && scale > 0) {
                put(VirtualHealthDeviceManager.KEY_PHONE_BATTERY, HealthReading(numberValue = level * 100.0 / scale))
            }
            put(VirtualHealthDeviceManager.KEY_PHONE_CHARGING, HealthReading(boolValue = charging))
            put(
                VirtualHealthDeviceManager.KEY_PHONE_MODEL,
                HealthReading(stringValue = "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"),
            )
            put(
                VirtualHealthDeviceManager.KEY_ANDROID_VERSION,
                HealthReading(stringValue = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"),
            )
            put(VirtualHealthDeviceManager.KEY_LAST_SYNC, HealthReading(stringValue = OffsetDateTime.now().toString()))
        }
    }

    private fun permissionFor(metric: HealthMetric): String = HealthPermission.getReadPermission(recordType(metric))

    private fun recordType(metric: HealthMetric): KClass<out Record> = when (metric) {
        HealthMetric.STEPS -> StepsRecord::class
        HealthMetric.HEART_RATE -> HeartRateRecord::class
        HealthMetric.RESTING_HEART_RATE -> RestingHeartRateRecord::class
        HealthMetric.SLEEP -> SleepSessionRecord::class
        HealthMetric.WEIGHT -> WeightRecord::class
        HealthMetric.OXYGEN_SATURATION -> OxygenSaturationRecord::class
        HealthMetric.BLOOD_PRESSURE_SYSTOLIC, HealthMetric.BLOOD_PRESSURE_DIASTOLIC -> BloodPressureRecord::class
        HealthMetric.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
        HealthMetric.DISTANCE -> DistanceRecord::class
        HealthMetric.BODY_TEMPERATURE -> BodyTemperatureRecord::class
        HealthMetric.RESPIRATORY_RATE -> RespiratoryRateRecord::class
        HealthMetric.HRV -> HeartRateVariabilityRmssdRecord::class
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)
}

data class HealthReading(
    val numberValue: Double? = null,
    val stringValue: String? = null,
    val boolValue: Boolean? = null,
)
