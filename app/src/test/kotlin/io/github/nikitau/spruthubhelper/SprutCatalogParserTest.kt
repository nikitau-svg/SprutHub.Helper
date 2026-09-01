package io.github.nikitau.spruthubhelper

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.ServiceCardTemplate
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
    fun keepsAllEightQingpingValuesAcrossFiveReadOnlyServices() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Спальня Никита"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":120,"name":"Qingping Air Monitor Lite","roomId":1,"services":[
                {"id":1,"type":"AirQualitySensor","name":"Air Quality","characteristics":[
                  {"id":1,"type":"AirQuality","control":{"write":false,"value":{"intValue":1}}},
                  {"id":2,"type":"PM2_5Density","control":{"write":false,"value":{"doubleValue":8}}},
                  {"id":3,"type":"PM10Density","control":{"write":false,"value":{"doubleValue":8}}}
                ]},
                {"id":2,"type":"CarbonDioxideSensor","name":"CO2","characteristics":[
                  {"id":1,"type":"CarbonDioxideDetected","control":{"write":false,"value":{"intValue":0}}},
                  {"id":2,"type":"CarbonDioxideLevel","control":{"write":false,"value":{"doubleValue":456}}}
                ]},
                {"id":3,"type":"BatteryService","name":"Battery","characteristics":[
                  {"id":1,"type":"BatteryLevel","unit":"percentage","control":{"write":false,"value":{"intValue":100}}}
                ]},
                {"id":4,"type":"TemperatureSensor","name":"Temperature","characteristics":[
                  {"id":1,"type":"CurrentTemperature","unit":"celsius","control":{"write":false,"value":{"doubleValue":23.8}}}
                ]},
                {"id":5,"type":"HumiditySensor","name":"Humidity","characteristics":[
                  {"id":1,"type":"CurrentRelativeHumidity","unit":"percentage","control":{"write":false,"value":{"intValue":67}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls
        val group = io.github.nikitau.spruthubhelper.data.groupControlsByAccessory(controls).single()
        val valuesByService = group.serviceCards.associate { card ->
            group.serviceLabel(card) to card.characteristicValues().associate { it.label to it.value }
        }

        assertEquals(8, controls.size)
        assertEquals(5, group.serviceCards.size)
        assertEquals(
            mapOf("Качество воздуха" to "Отличное", "PM2.5" to "8", "PM10" to "8"),
            valuesByService.getValue("Качество воздуха"),
        )
        assertEquals(
            mapOf("CO₂ обнаружен" to "Нет", "CO₂" to "456"),
            valuesByService.getValue("CO₂"),
        )
        assertEquals(mapOf("Батарея" to "100 %"), valuesByService.getValue("Батарея"))
        assertEquals(mapOf("Сейчас" to "23.8 °C"), valuesByService.getValue("Температура"))
        assertEquals(mapOf("Влажность" to "67 %"), valuesByService.getValue("Влажность"))
    }

    @Test
    fun keepsDirectPrimitiveValuesFromCatalogShape() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":121,"name":"Редкий датчик","roomId":1,"services":[
                {"id":1,"type":"C_AtmosphericPressureSensor","name":"Атмосфера","characteristics":[
                  {"id":1,"type":"C_CurrentAtmosphericPressure","name":"Давление","read":true,
                   "write":false,"value":1008.4,"unit":"@unit_hpa"},
                  {"id":2,"type":"C_Online","name":"На связи","read":true,
                   "write":false,"value":true},
                  {"id":3,"type":"C_String","name":"Состояние","read":true,
                   "write":false,"value":"Стабильно"}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls

        assertEquals(3, controls.size)
        assertEquals(1008.4, controls.single { it.characteristicId == "1" }.value.numberValue!!, 0.0)
        assertEquals(true, controls.single { it.characteristicId == "2" }.value.boolValue)
        assertEquals("Стабильно", controls.single { it.characteristicId == "3" }.value.stringValue)
    }

    @Test
    fun readsCatalogValuesArrayWithoutMistakingItForCurrentState() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":122,"name":"Очиститель","roomId":1,"services":[
                {"id":1,"type":"AirPurifier","characteristics":[
                  {"id":1,"type":"TargetAirPurifierState","name":"Режим","read":true,"write":true,
                   "value":1,"values":[
                     {"value":0,"name":"Ручной"},
                     {"value":1,"name":"Авто"}
                   ]},
                  {"id":2,"type":"C_Integer","name":"Только варианты","read":true,"write":false,
                   "values":[{"value":10,"name":"Десять"},{"value":20,"name":"Двадцать"}]}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls
        val mode = controls.single()

        assertEquals(ControlBehavior.OPTIONS, mode.behavior)
        assertEquals(listOf("Ручной", "Авто"), mode.valueOptions.map { it.name })
        assertEquals(1.0, mode.value.numberValue!!, 0.0)
        assertEquals("intValue", mode.valueField)
        assertEquals("Авто", buildServiceControlCards(controls).single().headlineValue())
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
    fun readsLocalizedNamesAndLinkedServiceReferences() {
        val rooms = json.parseToJsonElement(
            """{"rooms":[{"id":1,"name":{"ru":"Гостиная"}}]}""",
        )
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":70,"name":{"ru":"Климатическая станция"},"roomId":1,"services":[
                {"id":10,"type":"Thermostat","name":{"ru":"Климат"},"primary":true,
                 "linkedServices":[11,{"sId":12},{"serviceId":"13"}],"characteristics":[
                  {"id":1,"type":"C_ACTIVE","name":{"ru":"Питание"},
                   "control":{"write":true,"value":{"boolValue":true}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val control = parser.parse(rooms, accessories).controls.single()

        assertEquals("Климатическая станция", control.title)
        assertEquals("Гостиная", control.room)
        assertEquals("Климат", control.serviceName)
        assertEquals("Питание", control.characteristicName)
        assertEquals(true, control.servicePrimary)
        assertEquals(listOf("11", "12", "13"), control.linkedServiceIds)
    }

    @Test
    fun classifiesKnownServiceTypeBeforeAccessoryName() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":80,"name":"Лампа у окна","roomId":1,"services":[
                {"id":1,"type":"LightSensor","characteristics":[
                  {"id":1,"type":"CurrentAmbientLightLevel","control":{"write":false,"value":{"doubleValue":120}}}
                ]},
                {"id":2,"type":"AirPurifier","characteristics":[
                  {"id":1,"type":"C_ACTIVE","control":{"write":true,"value":{"intValue":1}}}
                ]},
                {"id":3,"type":"IrrigationSystem","characteristics":[
                  {"id":1,"type":"C_ACTIVE","control":{"write":true,"value":{"intValue":0}}}
                ]},
                {"id":4,"type":"C_WattMeter","characteristics":[
                  {"id":1,"type":"C_WATT","control":{"write":false,"value":{"doubleValue":42}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controlsByService = parser.parse(rooms, accessories).controls.associateBy(SprutControl::serviceId)

        assertEquals(DeviceKind.SENSOR, controlsByService.getValue("1").kind)
        assertEquals(DeviceKind.FAN, controlsByService.getValue("2").kind)
        assertEquals(DeviceKind.VALVE, controlsByService.getValue("3").kind)
        assertEquals(DeviceKind.SENSOR, controlsByService.getValue("4").kind)
    }

    @Test
    fun hidesUnsupportedCameraManagementServicesInsteadOfCreatingGenericCards() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":81,"name":"Камера с прожектором","roomId":1,"services":[
                {"id":1,"type":"CameraControl","characteristics":[
                  {"id":1,"type":"Active","control":{"write":true,"value":{"intValue":1}}}
                ]},
                {"id":2,"type":"CameraRecordingManagement","characteristics":[
                  {"id":1,"type":"Active","control":{"write":true,"value":{"intValue":1}}}
                ]},
                {"id":3,"type":"Lightbulb","name":"Прожектор","characteristics":[
                  {"id":1,"type":"On","control":{"write":true,"value":{"boolValue":false}}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls

        assertEquals(listOf("3"), controls.map(SprutControl::serviceId))
        assertEquals(DeviceKind.LIGHT, controls.single().kind)
    }

    @Test
    fun keepsServerValueLabelsForUnknownEnumerations() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":90,"name":"Пылесос","roomId":1,"services":[
                {"sId":4,"type":"C_VacuumCleaner","name":"Уборка","characteristics":[
                  {"cId":8,"control":{"name":"Режим","type":"C_CleanMode","write":false,
                   "value":{"intValue":2},"validValues":[
                     {"value":{"intValue":0},"key":"AUTO","name":"Авто"},
                     {"value":{"intValue":2},"key":"TURBO","name":"Турбо"}
                   ]}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val control = parser.parse(rooms, accessories).controls.single()

        assertEquals(2, control.valueOptions.size)
        assertEquals("TURBO", control.valueOptions.last().key)
        assertEquals("Турбо", buildServiceControlCards(listOf(control)).single().headlineValue())
    }

    @Test
    fun exposesWritableEnumerationsAsSafeOptionControls() {
        val rooms = json.parseToJsonElement("""{"rooms":[{"id":1,"name":"Дом"}]}""")
        val accessories = json.parseToJsonElement(
            """
            {"accessories":[{
              "id":96,"name":"Охрана","roomId":1,"services":[
                {"sId":4,"type":"SecuritySystem","name":"Сигнализация","characteristics":[
                  {"cId":1,"type":"SecuritySystemCurrentState","control":{"write":false,"value":{"intValue":3}}},
                  {"cId":2,"type":"SecuritySystemTargetState","control":{"write":true,
                   "value":{"intValue":1},"validValues":[
                     {"value":{"intValue":0},"key":"STAY","name":"Дома"},
                     {"value":{"intValue":1},"key":"AWAY","name":"Вне дома"},
                     {"value":{"intValue":2},"key":"NIGHT","name":"Ночь"},
                     {"value":{"intValue":3},"key":"OFF","name":"Снять"}
                   ]}}
                ]}
              ]
            }]}
            """.trimIndent(),
        )

        val controls = parser.parse(rooms, accessories).controls
        val option = controls.single { it.behavior == ControlBehavior.OPTIONS }
        val card = buildServiceControlCards(controls).single()

        assertEquals(DeviceKind.SECURITY, option.kind)
        assertEquals(true, option.writable)
        assertEquals(4, option.valueOptions.size)
        assertEquals(ServiceCardTemplate.SECURITY, card.template)
        assertEquals("Вне дома", card.headlineValue())
        assertEquals(listOf(option.id), card.optionControls().map(SprutControl::id))
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
    fun readsDirectPrimitiveCharacteristicEvent() {
        val event = json.parseToJsonElement(
            """
            {"event":{"characteristic":{"event":"EVENT_UPDATE","characteristics":[
              {"aId":7,"sId":11,"cId":9,"type":"Double","value":456.5}
            ]}}}
            """.trimIndent(),
        )

        val update = parser.parseUpdate(event)

        assertNotNull(update)
        assertEquals("9", update?.characteristicId)
        assertEquals(456.5, update?.value?.numberValue!!, 0.0)
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
