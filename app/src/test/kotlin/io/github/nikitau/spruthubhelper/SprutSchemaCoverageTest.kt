package io.github.nikitau.spruthubhelper

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.groupControlsByAccessory
import io.github.nikitau.spruthubhelper.data.readableSprutUnit
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SprutSchemaCoverageTest {
    private val json = Json
    private val parser = SprutCatalogParser()

    private val manifest: JsonObject by lazy {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream("sprut-schema-types.json")) {
            "Не найден тестовый срез типов SprutHub"
        }.bufferedReader().use { it.readText() }
        json.parseToJsonElement(text).jsonObject
    }

    private val serviceTypes: List<String>
        get() = manifest.getValue("serviceTypes").jsonArray.map { it.jsonPrimitive.content }

    private val characteristicTypes: List<String>
        get() = manifest.getValue("characteristicTypes").jsonArray.map { it.jsonPrimitive.content }

    private val units: List<String>
        get() = manifest.getValue("units").jsonArray.map { it.jsonPrimitive.content }

    private val enumCharacteristics: Map<String, List<String>>
        get() = manifest.getValue("enumCharacteristics").jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.content }
        }

    @Test
    fun schemaSnapshotRetainsTheFullPublishedTypeBreadth() {
        assertEquals("http://json-schema.org/draft-07/schema#", manifest.getValue("schemaDraft").jsonPrimitive.content)
        assertEquals(
            "a997d659b0ce41b8fb90899bd294444690e422782d992004fb85f620fe90620a",
            manifest.getValue("sourceSha256").jsonPrimitive.content,
        )
        assertEquals(103, serviceTypes.size)
        assertEquals(103, serviceTypes.distinct().size)
        assertEquals(313, characteristicTypes.size)
        assertEquals(313, characteristicTypes.distinct().size)
        assertEquals(33, serviceTypes.count { it.startsWith("C_") })
        assertEquals(67, characteristicTypes.count { it.startsWith("C_") })
        assertEquals(81, units.size)
        assertEquals(81, units.distinct().size)
        assertEquals(69, enumCharacteristics.size)
        assertTrue(enumCharacteristics.values.all { it.isNotEmpty() })
    }

    @Test
    fun everySchemaLocaleUnitHasAHumanReadableLabel() {
        val unresolved = units
            .filter { it.startsWith("@unit_") }
            .filter { readableSprutUnit(it) == it }

        assertEquals("Не переведены единицы SprutHub", emptyList<String>(), unresolved)
        assertTrue(units.all { readableSprutUnit(it).isNotBlank() })
    }

    @Test
    fun everyNestedSchemaEnumerationKeepsAllServerOptions() {
        val characteristics = buildJsonArray {
            enumCharacteristics.toSortedMap().entries.forEachIndexed { index, (type, keys) ->
                add(
                    buildJsonObject {
                        put("id", index + 1)
                        put("type", type)
                        put("read", true)
                        put("write", true)
                        put("value", 0)
                        putJsonArray("validValues") {
                            keys.forEachIndexed { optionIndex, key ->
                                add(
                                    buildJsonObject {
                                        put("value", optionIndex)
                                        put("key", key)
                                        put("name", key)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        val service = buildJsonObject {
            put("id", 1)
            put("type", "GenericService")
            put("name", "Перечисления схемы")
            put("characteristics", characteristics)
        }
        val controls = parser.parse(
            deeplyNestedRooms(),
            deeplyNestedAccessories(JsonArray(listOf(service))),
        ).controls
        val controlsByType = controls.associateBy { it.characteristicType }

        assertEquals(enumCharacteristics.keys, controlsByType.keys)
        enumCharacteristics.forEach { (type, keys) ->
            assertEquals(
                "Потеряны варианты перечисления $type",
                keys,
                controlsByType.getValue(type).valueOptions.map { it.key },
            )
        }
    }

    @Test
    fun everySchemaServiceIsVisibleOrExplicitlyFilteredAsTechnical() {
        val services = buildJsonArray {
            serviceTypes.forEachIndexed { index, serviceType ->
                add(
                    buildJsonObject {
                        put("id", index + 1)
                        put("type", serviceType)
                        put("name", "Сервис $serviceType")
                        putJsonArray("characteristics") {
                            add(
                                buildJsonObject {
                                    put("id", 1)
                                    put("type", "C_String")
                                    put("read", true)
                                    put("write", false)
                                    put("value", "value-$index")
                                },
                            )
                        }
                    },
                )
            }
        }
        val accessories = deeplyNestedAccessories(services)
        val controls = parser.parse(deeplyNestedRooms(), accessories).controls
        val controlsByType = controls.associateBy { it.sourceType }
        val technicalTypes = serviceTypes.filterTo(linkedSetOf()) { type ->
            type in explicitlyTechnicalServices || type.startsWith("Camera")
        }
        val visibleTypes = serviceTypes.toSet() - technicalTypes

        assertEquals(visibleTypes, controlsByType.keys)
        assertEquals(visibleTypes.size, controls.size)
        assertTrue(controls.all { it.behavior == ControlBehavior.SENSOR })
        assertTrue(controls.all { it.value.stringValue?.startsWith("value-") == true })

        val group = groupControlsByAccessory(controls).single()
        assertEquals(visibleTypes.size, group.serviceCards.size)
        assertTrue(group.serviceCards.all { group.serviceLabel(it).isNotBlank() })

        representativeKinds.forEach { (type, expectedKind) ->
            assertEquals("Неверный тип сервиса $type", expectedKind, controlsByType.getValue(type).kind)
        }
    }

    @Test
    fun everySchemaCharacteristicSurvivesMixedRuntimeValueShapes() {
        val characteristics = buildJsonArray {
            characteristicTypes.forEachIndexed { index, characteristicType ->
                add(runtimeCharacteristic(index + 1, characteristicType, index))
            }
        }
        val service = buildJsonObject {
            put("id", 1)
            put("type", "GenericService")
            put("name", "Полная матрица характеристик")
            put("characteristics", characteristics)
        }
        val controls = parser.parse(
            deeplyNestedRooms(),
            deeplyNestedAccessories(JsonArray(listOf(service))),
        ).controls
        val metadataIds = characteristicTypes.mapIndexedNotNull { index, type ->
            (index + 1).toString().takeIf { type == "Name" }
        }.toSet()
        val expectedIds = characteristicTypes.indices.map { (it + 1).toString() }.toSet() - metadataIds

        assertEquals(expectedIds, controls.map { it.characteristicId }.toSet())
        assertEquals(expectedIds.size, controls.size)
        assertTrue(controls.all { it.behavior == ControlBehavior.SENSOR })
        assertTrue(controls.all { it.displayValue != "—" })
    }

    @Test
    fun representativeServiceFamiliesKeepTheirRealControlSemantics() {
        val services = json.parseToJsonElement(
            """
            [
              {"id":1,"type":"GarageDoorOpener","characteristics":[
                {"id":1,"type":"CurrentDoorState","write":false,"value":1},
                {"id":2,"type":"TargetDoorState","write":true,"value":0}
              ]},
              {"id":2,"type":"Valve","characteristics":[
                {"id":1,"type":"Active","write":true,"value":true},
                {"id":2,"type":"InUse","write":false,"value":true}
              ]},
              {"id":3,"type":"Faucet","characteristics":[
                {"id":1,"type":"Active","write":true,"value":false}
              ]},
              {"id":4,"type":"HumidifierDehumidifier","characteristics":[
                {"id":1,"type":"Active","write":true,"value":true},
                {"id":2,"type":"TargetRelativeHumidity","write":true,"value":55,"minValue":30,"maxValue":80},
                {"id":3,"type":"CurrentRelativeHumidity","write":false,"value":48}
              ]},
              {"id":5,"type":"Window","characteristics":[
                {"id":1,"type":"CurrentPosition","write":false,"value":25},
                {"id":2,"type":"TargetPosition","write":true,"value":75,"minValue":0,"maxValue":100}
              ]},
              {"id":6,"type":"Door","characteristics":[
                {"id":1,"type":"CurrentPosition","write":false,"value":0},
                {"id":2,"type":"TargetPosition","write":true,"value":100,"minValue":0,"maxValue":100}
              ]},
              {"id":7,"type":"PowerManagement","characteristics":[
                {"id":1,"type":"On","write":true,"value":true}
              ]},
              {"id":8,"type":"C_Option","characteristics":[
                {"id":1,"type":"C_Boolean","write":true,"value":false}
              ]},
              {"id":9,"type":"C_TemperatureControl","characteristics":[
                {"id":1,"type":"On","write":true,"value":true},
                {"id":2,"type":"TargetTemperature","write":true,"value":22.5,"minValue":10,"maxValue":35},
                {"id":3,"type":"CurrentTemperature","write":false,"value":21.7,"unit":"@unit_celsius"}
              ]},
              {"id":10,"type":"C_VacuumCleaner","characteristics":[
                {"id":1,"type":"C_TargetOperationalState","write":true,"value":1,"validValues":[
                  {"value":0,"name":"Стоп"},{"value":1,"name":"Уборка"},{"value":2,"name":"Пауза"}
                ]},
                {"id":2,"type":"C_Progress","write":false,"value":42,"unit":"@unit_percent"}
              ]},
              {"id":11,"type":"Television","characteristics":[
                {"id":1,"type":"Active","write":true,"value":true},
                {"id":2,"type":"CurrentMediaState","write":false,"value":1}
              ]},
              {"id":12,"type":"FanBasic","characteristics":[
                {"id":1,"type":"Active","write":true,"value":true},
                {"id":2,"type":"RotationSpeed","write":true,"value":35,"minValue":0,"maxValue":100},
                {"id":3,"type":"CurrentFanState","write":false,"value":2}
              ]}
            ]
            """.trimIndent(),
        ).jsonArray
        val controls = parser.parse(
            deeplyNestedRooms(),
            deeplyNestedAccessories(services),
        ).controls
        val actionsByType = controls
            .filter { it.behavior != ControlBehavior.SENSOR }
            .associateBy { it.sourceType }
        val expected = mapOf(
            "GarageDoorOpener" to (DeviceKind.GARAGE to ControlBehavior.TOGGLE),
            "Valve" to (DeviceKind.VALVE to ControlBehavior.TOGGLE),
            "Faucet" to (DeviceKind.VALVE to ControlBehavior.TOGGLE),
            "HumidifierDehumidifier" to (DeviceKind.THERMOSTAT to ControlBehavior.TOGGLE_RANGE),
            "Window" to (DeviceKind.CURTAIN to ControlBehavior.RANGE),
            "Door" to (DeviceKind.CURTAIN to ControlBehavior.RANGE),
            "PowerManagement" to (DeviceKind.SWITCH to ControlBehavior.TOGGLE),
            "C_Option" to (DeviceKind.SWITCH to ControlBehavior.TOGGLE),
            "C_TemperatureControl" to (DeviceKind.THERMOSTAT to ControlBehavior.TOGGLE_RANGE),
            "C_VacuumCleaner" to (DeviceKind.VACUUM to ControlBehavior.OPTIONS),
            "Television" to (DeviceKind.TELEVISION to ControlBehavior.TOGGLE),
            "FanBasic" to (DeviceKind.FAN to ControlBehavior.TOGGLE_RANGE),
        )

        assertEquals(expected.keys, actionsByType.keys)
        expected.forEach { (type, contract) ->
            val action = actionsByType.getValue(type)
            assertEquals("Неверный DeviceKind у $type", contract.first, action.kind)
            assertEquals("Неверное управление у $type", contract.second, action.behavior)
        }
        assertEquals(
            setOf("1", "2", "4", "5", "6", "9", "10", "11", "12"),
            controls.filter { it.behavior == ControlBehavior.SENSOR }.map { it.serviceId }.toSet(),
        )
    }

    private fun runtimeCharacteristic(id: Int, type: String, shape: Int): JsonObject = buildJsonObject {
        put("id", id)
        put("type", type)
        put("read", true)
        put("write", false)
        when (shape % 8) {
            0 -> put("value", shape % 2 == 0)
            1 -> put("value", shape)
            2 -> put("value", shape + 0.5)
            3 -> put("value", "value-$shape")
            4 -> putJsonObject("control") {
                put("write", false)
                putJsonObject("value") { put("boolValue", shape % 2 == 0) }
            }
            5 -> putJsonObject("control") {
                put("write", false)
                putJsonObject("value") { put("intValue", shape) }
            }
            6 -> putJsonObject("control") {
                put("write", false)
                putJsonObject("value") { put("doubleValue", shape + 0.25) }
            }
            else -> putJsonObject("control") {
                put("write", false)
                putJsonObject("value") { put("stringValue", "value-$shape") }
            }
        }
    }

    private fun deeplyNestedRooms() = buildJsonObject {
        putJsonObject("response") {
            putJsonObject("room") {
                putJsonObject("list") {
                    putJsonArray("rooms") {
                        add(buildJsonObject { put("id", 1); put("name", "Тестовая комната") })
                    }
                }
            }
        }
    }

    private fun deeplyNestedAccessories(services: JsonArray) = buildJsonObject {
        putJsonObject("response") {
            putJsonObject("result") {
                putJsonObject("data") {
                    putJsonObject("accessory") {
                        putJsonObject("list") {
                            putJsonArray("accessories") {
                                add(
                                    buildJsonObject {
                                        put("id", 900)
                                        put("name", "Матрица схемы")
                                        put("roomId", 1)
                                        put("services", services)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val explicitlyTechnicalServices = setOf(
            "AccessControl",
            "AccessoryInformation",
            "AudioStreamManagement",
            "CloudRelay",
            "DataStreamTransportManagement",
            "Diagnostics",
            "HAPProtocolInformation",
            "ServiceLabel",
            "Siri",
            "TargetControlManagement",
            "ThreadTransport",
            "TransferTransportManagement",
            "WiFiTransport",
        )

        val representativeKinds = mapOf(
            "GenericService" to DeviceKind.OTHER,
            "AirPurifier" to DeviceKind.FAN,
            "AirQualitySensor" to DeviceKind.SENSOR,
            "BatteryService" to DeviceKind.SENSOR,
            "CarbonDioxideSensor" to DeviceKind.SENSOR,
            "ContactSensor" to DeviceKind.SENSOR,
            "Door" to DeviceKind.CURTAIN,
            "Doorbell" to DeviceKind.SWITCH,
            "FanBasic" to DeviceKind.FAN,
            "Faucet" to DeviceKind.VALVE,
            "GarageDoorOpener" to DeviceKind.GARAGE,
            "HeaterCooler" to DeviceKind.THERMOSTAT,
            "HumidifierDehumidifier" to DeviceKind.THERMOSTAT,
            "IrrigationSystem" to DeviceKind.VALVE,
            "Lightbulb" to DeviceKind.LIGHT,
            "LockMechanism" to DeviceKind.LOCK,
            "Outlet" to DeviceKind.OUTLET,
            "SecuritySystem" to DeviceKind.SECURITY,
            "Slat" to DeviceKind.BLINDS,
            "Speaker" to DeviceKind.TELEVISION,
            "StatelessProgrammableSwitch" to DeviceKind.SWITCH,
            "Television" to DeviceKind.TELEVISION,
            "Thermostat" to DeviceKind.THERMOSTAT,
            "Valve" to DeviceKind.VALVE,
            "WindowCovering" to DeviceKind.BLINDS,
            "C_AtmosphericPressureSensor" to DeviceKind.SENSOR,
            "C_VoltMeter" to DeviceKind.SENSOR,
            "C_WaterMeter" to DeviceKind.SENSOR,
            "C_Option" to DeviceKind.SWITCH,
            "C_PetFeeder" to DeviceKind.SWITCH,
            "C_VacuumCleaner" to DeviceKind.VACUUM,
            "C_GasMeter" to DeviceKind.SENSOR,
            "C_TemperatureControl" to DeviceKind.THERMOSTAT,
        )
    }
}
