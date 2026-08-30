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

    private fun control(id: String, serviceId: String, subtitle: String) = SprutControl(
        id = id,
        accessoryId = "7",
        serviceId = serviceId,
        characteristicId = "1",
        title = "Комната",
        subtitle = subtitle,
        room = "Дом",
    )
}
