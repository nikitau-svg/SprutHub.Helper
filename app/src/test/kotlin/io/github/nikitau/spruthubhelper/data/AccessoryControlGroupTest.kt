package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessoryControlGroupTest {
    @Test
    fun groupsSeveralServicesWithoutDroppingActions() {
        val controls = listOf(
            control(id = "7:1:1", serviceId = "1", subtitle = "Свет"),
            control(id = "7:2:1", serviceId = "2", subtitle = "Вентилятор"),
        )

        val group = groupControlsByAccessory(controls).single()

        assertEquals("accessory:7", group.key)
        assertEquals("Комната", group.title)
        assertEquals(2, group.controls.size)
        assertEquals(listOf("Свет", "Вентилятор"), group.controls.map(group::serviceLabel))
    }

    @Test
    fun removesOnlyExactDuplicateControlIds() {
        val original = control(id = "7:1:1", serviceId = "1", subtitle = "Свет")
        val secondService = control(id = "7:2:1", serviceId = "2", subtitle = "Вентилятор")

        val group = groupControlsByAccessory(listOf(original, original.copy(), secondService)).single()

        assertEquals(listOf("7:1:1", "7:2:1"), group.controls.map(SprutControl::id))
    }

    @Test
    fun groupsCharacteristicsOfOneServiceIntoOneLogicalCard() {
        val main = control(id = "11:13:main", serviceId = "13", subtitle = "Кондиционер").copy(
            behavior = ControlBehavior.TOGGLE_RANGE,
            characteristicId = "18",
            rangeCharacteristicId = "19",
            characteristicType = "C_ACTIVE",
            rangeCharacteristicType = "C_COOLING_THRESHOLD_TEMPERATURE",
            serviceName = "Кондиционер",
            sourceType = "HeaterCooler",
            kind = DeviceKind.THERMOSTAT,
            value = SprutValue(boolValue = true, numberValue = 22.0),
            unit = "celsius",
        )
        val currentTemperature = control(id = "11:13:20", serviceId = "13", subtitle = "Текущая температура").copy(
            behavior = ControlBehavior.SENSOR,
            characteristicId = "20",
            writable = false,
            characteristicType = "C_CURRENT_TEMPERATURE",
            characteristicName = "Текущая температура",
            value = SprutValue(numberValue = 23.5),
            unit = "celsius",
        )
        val mode = control(id = "11:13:21", serviceId = "13", subtitle = "Режим").copy(
            behavior = ControlBehavior.SENSOR,
            characteristicId = "21",
            writable = false,
            characteristicType = "C_CURRENT_HEATER_COOLER_STATE",
            value = SprutValue(numberValue = 3.0),
        )

        val card = buildServiceControlCards(listOf(main, currentTemperature, mode)).single()

        assertEquals("service:11:13", card.id)
        assertEquals("11:13:main", card.primaryControl.id)
        assertEquals(listOf("11:13:20", "11:13:21"), card.defaultAttributes().map(SprutControl::id))
        assertEquals("Включено · 22 °C", card.headlineValue())
        assertEquals("Сейчас", card.attributeLabel(currentTemperature))
        assertEquals("Охлаждение", card.attributeValue(mode))
    }

    private fun control(id: String, serviceId: String, subtitle: String) = SprutControl(
        id = id,
        accessoryId = id.substringBefore(':'),
        serviceId = serviceId,
        characteristicId = "1",
        title = "Комната",
        subtitle = subtitle,
        room = "Дом",
    )
}
