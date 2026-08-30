package io.github.nikitau.spruthubhelper

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SprutCatalogParserTest {
    private val json = Json
    private val parser = SprutCatalogParser()

    @Test
    fun combinesLightPowerAndBrightnessIntoOneControl() {
        val rooms = json.parseToJsonElement(
            """{"room":{"list":{"rooms":[{"id":2,"name":"Гостиная"}]}}}""",
        )
        val accessories = json.parseToJsonElement(
            """
            {
              "accessory": {"list": {"accessories": [{
                "id": 7,
                "name": "Торшер",
                "roomId": 2,
                "services": [{
                  "id": 11,
                  "type": "S_LIGHTBULB",
                  "characteristics": [
                    {"id": 1, "type": "C_ON", "control": {"value": {"boolValue": true}}},
                    {"id": 2, "type": "C_BRIGHTNESS", "minValue": 0, "maxValue": 100,
                     "control": {"value": {"intValue": 42}}}
                  ]
                }]
              }]}}
            }
            """.trimIndent(),
        )

        val catalog = parser.parse(rooms, accessories)

        assertEquals(1, catalog.controls.size)
        val control = catalog.controls.single()
        assertEquals("7:11:main", control.id)
        assertEquals("Гостиная", control.room)
        assertEquals(DeviceKind.LIGHT, control.kind)
        assertEquals(ControlBehavior.TOGGLE_RANGE, control.behavior)
        assertEquals(true, control.value.boolValue)
        assertEquals(42.0, control.value.numberValue!!, 0.0)
        assertEquals("2", control.rangeCharacteristicId)
        assertEquals("intValue", control.rangeValueField)
    }

    @Test
    fun readsCharacteristicEvent() {
        val event = json.parseToJsonElement(
            """
            {"event":{"characteristic":{"event":"EVENT_UPDATE","characteristics":[
              {"aId":7,"sId":11,"cId":1,"control":{"value":{"boolValue":false}}}
            ]}}}
            """.trimIndent(),
        )

        val update = parser.parseUpdate(event)

        assertNotNull(update)
        assertEquals("7", update?.accessoryId)
        assertEquals("11", update?.serviceId)
        assertEquals("1", update?.characteristicId)
        assertEquals(false, update?.value?.boolValue)
    }
}
