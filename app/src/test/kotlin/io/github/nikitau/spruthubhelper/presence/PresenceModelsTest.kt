package io.github.nikitau.spruthubhelper.presence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceModelsTest {
    private val zone = PresenceZone(
        id = "zone-1",
        name = "Дом",
        latitude = 55.7558,
        longitude = 37.6173,
        radiusMeters = 150.0,
        roomId = "room-1",
        publishDistance = true,
    )

    @Test
    fun `zone names are unique ignoring case and spacing`() {
        assertTrue(samePresenceZoneName(" Дом ", "дом"))
        assertTrue(samePresenceZoneName("Загородный дом", "загородныйдом"))
        assertFalse(samePresenceZoneName("Дом", "Работа"))
    }

    @Test
    fun `failed draft can be resumed only with the same definition`() {
        assertTrue(
            samePresenceZoneDefinition(
                zone = zone,
                name = "дом",
                latitude = 55.7558,
                longitude = 37.6173,
                radiusMeters = 150.0,
                roomId = "room-1",
                publishDistance = true,
            ),
        )
        assertFalse(
            samePresenceZoneDefinition(
                zone = zone,
                name = "Дом",
                latitude = 55.7558,
                longitude = 37.6173,
                radiusMeters = 250.0,
                roomId = "room-1",
                publishDistance = true,
            ),
        )
    }

    @Test
    fun `legacy duplicate zones are reported without merging their ids`() {
        val duplicates = duplicatePresenceZoneNames(
            listOf(
                zone,
                zone.copy(id = "zone-2", name = " дом "),
                zone.copy(id = "zone-3", name = "Работа"),
            ),
        )

        assertEquals(setOf("Дом"), duplicates)
    }
}
