package io.github.nikitau.spruthubhelper

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.buildServiceControlCards
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
                    {"id": 1, "type": "C_ON", "control": {"write": true, "value": {"boolValue": true}}},
                    {"id": 2, "type": "C_BRIGHTNESS", "minValue": 0, "maxValue": 100,
                     "control": {"write": true, "value": {"intValue": 42}}}
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
    fun ignoresAccessoryInformationAndBuildsWritableHeaterCoolerControl() {
        val rooms = json.parseToJsonElement(
            """{"room":{"list":{"rooms":[{"id":1,"name":"Зал"}]}}}""",
        )
        val accessories = json.parseToJsonElement(
            """
            {
              "accessory": {"list": {"accessories": [{
                "id": 11,
                "name": "Midea AC",
                "roomId": 1,
                "services": [
                  {
                    "id": 1,
                    "type": "AccessoryInformation",
                    "name": "Информация об аксессуаре",
                    "characteristics": [
                      {"id": 4, "type": "C_IDENTIFY", "control": {"write": true, "value": {"boolValue": false}}}
                    ]
                  },
                  {
                    "id": 13,
                    "type": "HeaterCooler",
                    "name": "Кондиционер",
                    "characteristics": [
                      {"id": 18, "type": "C_ACTIVE", "control": {"write": true, "value": {"intValue": 1}}},
                      {"id": 20, "meta": {"typeName": "C_CURRENT_TEMPERATURE", "displayName": "Температура"},
                       "control": {"write": false, "value": {"doubleValue": 23.5}}},
                      {"id": 21, "type": "C_TARGET_HEATER_COOLER_STATE", "name": "Режим",
                       "control": {"write": true, "value": {"intValue": 2}}},
                      {"id": 22, "type": "C_HEATING_THRESHOLD_TEMPERATURE", "minValue": 17, "maxValue": 30,
                       "control": {"write": true, "value": {"doubleValue": 25.0}}},
                      {"id": 19, "type": "C_COOLING_THRESHOLD_TEMPERATURE", "minValue": 17, "maxValue": 30,
                       "control": {"write": true, "value": {"doubleValue": 22.0}}}
                    ]
                  }
                ]
              }]}}
            }
            """.trimIndent(),
        )

        val parsedControls = parser.parse(rooms, accessories).controls
        val control = parsedControls.single { it.behavior == ControlBehavior.TOGGLE_RANGE }

        assertEquals("11:13:main", control.id)
        assertEquals(DeviceKind.THERMOSTAT, control.kind)
        assertEquals(ControlBehavior.TOGGLE_RANGE, control.behavior)
        assertEquals("18", control.characteristicId)
        assertEquals("C_ACTIVE", control.characteristicType)
        assertEquals("C_COOLING_THRESHOLD_TEMPERATURE", control.rangeCharacteristicType)
        assertEquals("Кондиционер", control.serviceName)
        assertEquals("intValue", control.valueField)
        assertEquals("19", control.rangeCharacteristicId)
        assertEquals("doubleValue", control.rangeValueField)
        assertEquals(true, control.value.boolValue)
        assertEquals(22.0, control.value.numberValue!!, 0.0)
        val attributes = parsedControls.filter { it.behavior == ControlBehavior.SENSOR }
        val currentTemperature = attributes.single { it.characteristicId == "20" }
        assertEquals("20", currentTemperature.characteristicId)
        assertEquals("C_CURRENT_TEMPERATURE", currentTemperature.characteristicType)
        assertEquals(23.5, currentTemperature.value.numberValue!!, 0.0)
        val writableMode = attributes.single { it.characteristicId == "21" }
        assertEquals("Режим", writableMode.characteristicName)
        assertEquals(false, writableMode.writable)
        assertEquals(
            listOf("20", "21"),
            buildServiceControlCards(parsedControls).single().defaultAttributes().map(SprutControl::characteristicId),
        )
    }

    @Test
    fun parsesCommonServicesByWritableCapabilities() {
        val rooms = json.parseToJsonElement(
            """{"rooms":[{"id":1,"name":"Дом"}]}""",
        )
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":21,
              "name":"Комбинированное устройство",
              "roomId":1,
              "services":[
                {"id":1,"type":"Outlet","name":"Розетка","characteristics":[
                  {"id":1,"type":"C_ON","control":{"write":true,"value":{"boolValue":false}}}
                ]},
                {"id":2,"type":"TemperatureSensor","name":"Датчик температуры","characteristics":[
                  {"id":1,"type":"C_CURRENT_TEMPERATURE","control":{"write":false,"value":{"doubleValue":24.0}}}
                ]},
                {"id":3,"type":"WindowCovering","name":"Жалюзи","characteristics":[
                  {"id":1,"type":"C_CURRENT_POSITION","control":{"write":false,"value":{"intValue":20}}},
                  {"id":2,"type":"C_TARGET_POSITION","minValue":0,"maxValue":100,
                   "control":{"write":true,"value":{"intValue":60}}}
                ]},
                {"id":4,"type":"Fan","name":"Вентилятор","characteristics":[
                  {"id":1,"type":"C_ACTIVE","control":{"write":true,"value":{"intValue":1}}},
                  {"id":2,"type":"C_ROTATION_SPEED","minValue":0,"maxValue":100,
                   "control":{"write":true,"value":{"doubleValue":35.0}}}
                ]},
                {"id":5,"type":"LockMechanism","name":"Замок","characteristics":[
                  {"id":1,"type":"C_CURRENT_LOCK_STATE","control":{"write":false,"value":{"intValue":0}}},
                  {"id":2,"type":"C_TARGET_LOCK_STATE","control":{"write":true,"value":{"intValue":1}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val parsedControls = parser.parse(rooms, accessories).controls
        val controls = parsedControls
            .filter { it.behavior != ControlBehavior.SENSOR }
            .associateBy(SprutControl::serviceId)

        assertEquals(setOf("1", "3", "4", "5"), controls.keys)
        assertEquals(DeviceKind.OUTLET, controls.getValue("1").kind)
        assertEquals(ControlBehavior.TOGGLE, controls.getValue("1").behavior)
        assertEquals(DeviceKind.BLINDS, controls.getValue("3").kind)
        assertEquals(ControlBehavior.RANGE, controls.getValue("3").behavior)
        assertEquals("2", controls.getValue("3").characteristicId)
        assertEquals(DeviceKind.FAN, controls.getValue("4").kind)
        assertEquals(ControlBehavior.TOGGLE_RANGE, controls.getValue("4").behavior)
        assertEquals(DeviceKind.LOCK, controls.getValue("5").kind)
        assertEquals(ControlBehavior.TOGGLE, controls.getValue("5").behavior)
        assertEquals(
            setOf("2", "3", "5"),
            parsedControls.filter { it.behavior == ControlBehavior.SENSOR }
                .map(SprutControl::serviceId)
                .toSet(),
        )
    }

    @Test
    fun keepsServiceAndCharacteristicNamesForMultiServiceAccessory() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":50,"name":"Комбинированный модуль","roomId":1,"services":[
                {"id":1,"type":"S_Switch","name":"Первый канал","characteristics":[
                  {"id":1,"type":"C_On","name":"Питание","control":{"write":true,"value":{"boolValue":false}}}
                ]},
                {"id":2,"type":"S_Switch","name":"Второй канал","characteristics":[
                  {"id":1,"type":"C_On","name":"Питание","control":{"write":true,"value":{"boolValue":true}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls

        assertEquals(2, controls.size)
        assertEquals(
            listOf("Первый канал · Питание", "Второй канал · Питание"),
            controls.map(SprutControl::subtitle),
        )
        assertEquals(2, controls.map(SprutControl::id).distinct().size)
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

    @Test
    fun readsEveryCharacteristicFromOneWebSocketEvent() {
        val event = json.parseToJsonElement(
            """
            {"event":{"characteristic":{"event":"EVENT_UPDATE","characteristics":[
              {"aId":7,"sId":11,"cId":1,"control":{"value":{"boolValue":false}}},
              {"aId":7,"sId":11,"cId":2,"control":{"value":{"doubleValue":37.5}}}
            ]}}}
            """.trimIndent(),
        )

        val updates = parser.parseUpdates(event)

        assertEquals(2, updates.size)
        assertEquals(listOf("1", "2"), updates.map { it.characteristicId })
        assertEquals(false, updates[0].value.boolValue)
        assertEquals(37.5, updates[1].value.numberValue!!, 0.0)
    }
}
