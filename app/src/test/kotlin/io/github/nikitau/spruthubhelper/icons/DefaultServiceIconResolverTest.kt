package io.github.nikitau.spruthubhelper.icons

import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.tiles.TileIconResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DefaultServiceIconResolverTest {
    @Test
    fun realCatalogServiceFamiliesReceiveSpecificIcons() {
        val cases = listOf(
            Triple("S.AirQualitySensor", DeviceKind.SENSOR, DefaultServiceIcon.AIR_QUALITY),
            Triple("S.CarbonDioxideSensor", DeviceKind.SENSOR, DefaultServiceIcon.CO2),
            Triple("S.BatteryService", DeviceKind.SENSOR, DefaultServiceIcon.BATTERY),
            Triple("S.TemperatureSensor", DeviceKind.SENSOR, DefaultServiceIcon.TEMPERATURE),
            Triple("S.HumiditySensor", DeviceKind.SENSOR, DefaultServiceIcon.HUMIDITY),
            Triple("S.ContactSensor", DeviceKind.SENSOR, DefaultServiceIcon.CONTACT),
            Triple("S.C_VacuumCleaner", DeviceKind.VACUUM, DefaultServiceIcon.VACUUM),
            Triple("S.Fan", DeviceKind.FAN, DefaultServiceIcon.FAN),
            Triple("S.Outlet", DeviceKind.OUTLET, DefaultServiceIcon.OUTLET),
            Triple("S.C_WattMeter", DeviceKind.SENSOR, DefaultServiceIcon.ELECTRICITY),
            Triple("S.Lightbulb", DeviceKind.LIGHT, DefaultServiceIcon.LIGHT),
            Triple("S.WindowCovering", DeviceKind.BLINDS, DefaultServiceIcon.BLINDS),
            Triple("scenario", DeviceKind.SCENE, DefaultServiceIcon.SCENE),
        )

        cases.forEach { (sourceType, kind, expected) ->
            assertEquals(sourceType, expected, resolve(sourceType = sourceType, kind = kind))
        }
    }

    @Test
    fun standardServiceTypeWinsOverAnAmbiguousEditableLabel() {
        assertEquals(
            DefaultServiceIcon.CO2,
            resolve(
                sourceType = "S.CarbonDioxideSensor",
                descriptor = "Лампочка у окна",
                kind = DeviceKind.SENSOR,
            ),
        )
    }

    @Test
    fun universalPhoneAndHealthServicesUseTheirMeaning() {
        val cases = mapOf(
            "Пульс синхронизации" to DefaultServiceIcon.SYNC,
            "Шаги сегодня" to DefaultServiceIcon.STEPS,
            "Пульс в покое" to DefaultServiceIcon.HEART,
            "Последний сон" to DefaultServiceIcon.SLEEP,
            "Вес" to DefaultServiceIcon.WEIGHT,
            "Кислород в крови" to DefaultServiceIcon.OXYGEN,
            "Давление верхнее" to DefaultServiceIcon.BLOOD_PRESSURE,
            "Активные калории" to DefaultServiceIcon.CALORIES,
            "Дистанция сегодня" to DefaultServiceIcon.DISTANCE,
            "Температура тела" to DefaultServiceIcon.TEMPERATURE,
            "Частота дыхания" to DefaultServiceIcon.RESPIRATORY,
            "Заряд аккумулятора" to DefaultServiceIcon.BATTERY,
            "Подключена зарядка" to DefaultServiceIcon.CHARGING,
            "Интернет доступен" to DefaultServiceIcon.NETWORK,
            "Модель телефона" to DefaultServiceIcon.PHONE,
            "Яркость экрана" to DefaultServiceIcon.DISPLAY,
            "Режим Не беспокоить" to DefaultServiceIcon.AUDIO,
        )

        cases.forEach { (label, expected) ->
            assertEquals(
                label,
                expected,
                resolve(sourceType = "S.C_Option", descriptor = label, kind = DeviceKind.SWITCH),
            )
        }
    }

    @Test
    fun everyBehaviorKindHasANonPowerSpecificFallbackWherePossible() {
        val genericPower = TileIconResolver.resource(DefaultServiceIcon.OTHER)
        DeviceKind.entries
            .filterNot { it == DeviceKind.OTHER }
            .forEach { kind ->
                assertNotEquals(kind.name, genericPower, TileIconResolver.resource(kind))
            }
    }

    @Test
    fun sensorSubtypesResolveToDifferentDrawableResources() {
        val air = control(sourceType = "S.AirQualitySensor", kind = DeviceKind.SENSOR)
        val battery = control(sourceType = "S.BatteryService", kind = DeviceKind.SENSOR)
        val temperature = control(sourceType = "S.TemperatureSensor", kind = DeviceKind.SENSOR)

        assertEquals(R.drawable.ic_device_air_quality, TileIconResolver.resource(air))
        assertEquals(R.drawable.ic_device_battery, TileIconResolver.resource(battery))
        assertEquals(R.drawable.ic_device_temperature, TileIconResolver.resource(temperature))
    }

    private fun resolve(
        sourceType: String,
        descriptor: String = "",
        kind: DeviceKind,
    ): DefaultServiceIcon = DefaultServiceIconResolver.resolve(
        kind = kind,
        sourceType = sourceType,
        descriptor = descriptor,
    )

    private fun control(sourceType: String, kind: DeviceKind) = SprutControl(
        id = "1:1:1",
        accessoryId = "1",
        serviceId = "1",
        characteristicId = "1",
        title = "Устройство",
        kind = kind,
        behavior = ControlBehavior.SENSOR,
        sourceType = sourceType,
    )
}
