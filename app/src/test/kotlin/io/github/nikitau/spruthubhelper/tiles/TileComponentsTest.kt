package io.github.nikitau.spruthubhelper.tiles

import io.github.nikitau.spruthubhelper.R
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.TileAssignment
import io.github.nikitau.spruthubhelper.data.TileLabelStyle
import io.github.nikitau.spruthubhelper.data.updateTileAssignment
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TileComponentsTest {
    @Test
    fun exposesOnlyAssignedSlotsToAndroid() {
        val assignments = listOf(
            TileAssignment(slot = 2, controlId = "light"),
            TileAssignment(slot = 9, controlId = "climate"),
        )

        assertEquals(setOf(2, 9), assignedTileSlots(assignments))
        assertEquals(emptySet<Int>(), assignedTileSlots(emptyList()))
    }

    @Test
    fun ignoresSlotsOutsideDeclaredServices() {
        val assignments = listOf(
            TileAssignment(slot = 0, controlId = "bad-low"),
            TileAssignment(slot = 12, controlId = "valid"),
            TileAssignment(slot = 13, controlId = "bad-high"),
        )

        assertEquals(setOf(12), assignedTileSlots(assignments))
    }

    @Test
    fun readOnlySensorsDoNotUseThePowerGlyph() {
        assertEquals(R.drawable.ic_device_sensor, TileIconResolver.resource(DeviceKind.SENSOR))
        assertNotEquals(R.drawable.ic_device_other, TileIconResolver.resource(DeviceKind.SENSOR))
    }

    @Test
    fun oldTileAssignmentMigratesToRoomAndServiceLabel() {
        val assignment = Json.decodeFromString<TileAssignment>(
            """{"slot":2,"controlId":"climate"}""",
        )

        assertEquals(TileLabelStyle.ROOM_AND_SERVICE, assignment.labelStyle)
    }

    @Test
    fun movingAnAssignedControlPreservesItsChosenLabelStyleWithoutDuplicates() {
        val updated = updateTileAssignment(
            current = listOf(
                TileAssignment(2, "climate", TileLabelStyle.ACCESSORY),
                TileAssignment(5, "light"),
            ),
            slot = 5,
            controlId = "climate",
        )

        assertEquals(
            listOf(TileAssignment(5, "climate", TileLabelStyle.ACCESSORY)),
            updated,
        )
    }
}
