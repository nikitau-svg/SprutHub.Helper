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
    fun localizesRawServiceTypeNamesWithoutReplacingUserNames() {
        val light = control(id = "8:1:1", serviceId = "1", subtitle = "Lightbulb").copy(
            serviceName = "Lightbulb",
            sourceType = "Lightbulb",
        )
        val fan = control(id = "8:2:1", serviceId = "2", subtitle = "Fan").copy(
            serviceName = "Fan",
            sourceType = "Fan",
        )

        val group = groupControlsByAccessory(listOf(light, fan)).single()

        assertEquals(setOf("Свет", "Вентилятор"), group.serviceCards.map(group::serviceLabel).toSet())
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
        assertEquals("Включено", card.headlineValue())
        assertEquals("Задано", card.rangeLabel())
        assertEquals("22 °C", card.rangeValue())
        assertEquals("Сейчас", card.attributeLabel(currentTemperature))
        assertEquals("Охлаждение", card.attributeValue(mode))
    }

    @Test
    fun mergesLinkedSensorServicesIntoPrimaryActionCard() {
        val thermostat = control(id = "31:1:main", serviceId = "1", subtitle = "Климат").copy(
            behavior = ControlBehavior.TOGGLE_RANGE,
            serviceName = "Климат",
            servicePrimary = true,
            linkedServiceIds = listOf("2", "3"),
        )
        val temperature = control(id = "31:2:1", serviceId = "2", subtitle = "Температура").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            characteristicId = "1",
            characteristicType = "C_CURRENT_TEMPERATURE",
            linkedServiceIds = listOf("1"),
        )
        val humidity = control(id = "31:3:1", serviceId = "3", subtitle = "Влажность").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            characteristicId = "1",
            characteristicType = "C_CURRENT_RELATIVE_HUMIDITY",
            linkedServiceIds = listOf("1"),
        )

        val card = buildServiceControlCards(listOf(thermostat, temperature, humidity)).single()

        assertEquals("service:31:1", card.id)
        assertEquals("31:1:main", card.primaryControl.id)
        assertEquals(listOf("1", "2", "3"), card.memberServiceIds)
        assertEquals(listOf("31:2:1", "31:3:1"), card.availableAttributes().map(SprutControl::id))
    }

    @Test
    fun preservesSeveralLinkedWritableServicesAsSeparateCards() {
        val light = control(id = "42:1:1", serviceId = "1", subtitle = "Свет").copy(
            linkedServiceIds = listOf("2", "3"),
            servicePrimary = true,
        )
        val fan = control(id = "42:2:1", serviceId = "2", subtitle = "Вентилятор").copy(
            linkedServiceIds = listOf("1", "3"),
        )
        val temperature = control(id = "42:3:1", serviceId = "3", subtitle = "Температура").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            characteristicType = "C_CURRENT_TEMPERATURE",
            linkedServiceIds = listOf("1", "2"),
        )

        val cards = buildServiceControlCards(listOf(light, fan, temperature))

        assertEquals(listOf("service:42:1", "service:42:2", "service:42:3"), cards.map(ServiceControlCard::id))
        assertEquals(listOf("1"), cards[0].memberServiceIds)
        assertEquals(listOf("2"), cards[1].memberServiceIds)
        assertEquals(listOf("3"), cards[2].memberServiceIds)
    }

    @Test
    fun mergesLinkedReadOnlyServicesWithoutDroppingSameCharacteristicIds() {
        val temperature = control(id = "55:8:1", serviceId = "8", subtitle = "Температура").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            characteristicId = "1",
            characteristicType = "C_CURRENT_TEMPERATURE",
            servicePrimary = true,
            linkedServiceIds = listOf("9"),
        )
        val humidity = control(id = "55:9:1", serviceId = "9", subtitle = "Влажность").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            characteristicId = "1",
            characteristicType = "C_CURRENT_RELATIVE_HUMIDITY",
            linkedServiceIds = listOf("8"),
        )

        val card = buildServiceControlCards(listOf(temperature, humidity)).single()

        assertEquals("service:55:8", card.id)
        assertEquals(listOf("8", "9"), card.memberServiceIds)
        assertEquals(listOf("55:9:1"), card.availableAttributes().map(SprutControl::id))
        assertEquals(true, card.containsService("9"))
        assertEquals("service:55:8", listOf(card).findCardForService("55", "9")?.id)
    }

    @Test
    fun disambiguatesEqualAttributesWithLinkedServiceNames() {
        val climate = control(id = "61:1:main", serviceId = "1", subtitle = "Климат").copy(
            behavior = ControlBehavior.TOGGLE,
            linkedServiceIds = listOf("2", "3"),
        )
        val indoor = control(id = "61:2:1", serviceId = "2", subtitle = "Внутри").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            serviceName = "Внутри",
            characteristicType = "C_CURRENT_TEMPERATURE",
        )
        val outdoor = control(id = "61:3:1", serviceId = "3", subtitle = "Снаружи").copy(
            behavior = ControlBehavior.SENSOR,
            writable = false,
            serviceName = "Снаружи",
            characteristicType = "C_CURRENT_TEMPERATURE",
        )

        val card = buildServiceControlCards(listOf(climate, indoor, outdoor)).single()

        assertEquals("Внутри · Сейчас", card.attributeLabel(indoor))
        assertEquals("Снаружи · Сейчас", card.attributeLabel(outdoor))
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
