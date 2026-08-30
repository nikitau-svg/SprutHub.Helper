package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutRoom
import io.github.nikitau.spruthubhelper.data.SprutValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

class SprutCatalogParser {
    fun parse(
        roomsResponse: JsonElement,
        accessoriesResponse: JsonElement,
        scenariosResponse: JsonElement = JsonNull,
        hubVersion: String = "",
    ): SprutCatalog {
        val roomList = findArray(roomsResponse, "rooms")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { room ->
                SprutRoom(
                    id = room.scalar("id", "index", "roomId"),
                    name = room.scalar("name", "title").ifBlank { "Без комнаты" },
                )
            }
        val rooms = roomList.associate { it.id to it.name }

        val controls = buildList {
            findArray(accessoriesResponse, "accessories")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .forEachIndexed { accessoryIndex, accessory ->
                    addAll(parseAccessory(accessory, accessoryIndex, rooms))
                }
            addAll(parseScenarios(scenariosResponse))
        }.distinctBy(SprutControl::id).sortedWith(compareBy(SprutControl::room, SprutControl::title))

        return SprutCatalog(
            controls = controls,
            rooms = roomList,
            refreshedAtEpochMs = System.currentTimeMillis(),
            hubVersion = hubVersion,
        )
    }

    fun parseUpdate(event: JsonElement): CharacteristicUpdate? {
        val characteristic = findArray(event, "characteristics")
            ?.firstOrNull() as? JsonObject
            ?: findObject(event, "characteristic")
            ?: return null
        val accessoryId = characteristic.scalar("aId", "accessoryId")
        val serviceId = characteristic.scalar("sId", "serviceId")
        val characteristicId = characteristic.scalar("cId", "characteristicId", "id")
        if (accessoryId.isBlank() || serviceId.isBlank() || characteristicId.isBlank()) return null
        return CharacteristicUpdate(
            accessoryId = accessoryId,
            serviceId = serviceId,
            characteristicId = characteristicId,
            value = extractValue(characteristic),
        )
    }

    private fun parseAccessory(
        accessory: JsonObject,
        accessoryIndex: Int,
        rooms: Map<String, String>,
    ): List<SprutControl> {
        val accessoryId = accessory.scalar("id", "aId", "index").ifBlank { accessoryIndex.toString() }
        val accessoryName = accessory.scalar("name", "title", "displayName").ifBlank { "Устройство $accessoryId" }
        val roomId = accessory.scalar("roomId", "rId", "room")
        val roomName = rooms[roomId]
            ?: accessory.objectValue("room")?.scalar("name", "title")
            ?: "Без комнаты"
        val services = accessory.arrayValue("services")
            ?: findArray(accessory, "services")
            ?: JsonArray(emptyList())

        return services.mapNotNull { it as? JsonObject }.flatMapIndexed { serviceIndex, service ->
            parseService(accessoryId, accessoryName, roomName, service, serviceIndex)
        }
    }

    private fun parseService(
        accessoryId: String,
        accessoryName: String,
        room: String,
        service: JsonObject,
        serviceIndex: Int,
    ): List<SprutControl> {
        val serviceId = service.scalar("id", "sId", "index").ifBlank { serviceIndex.toString() }
        val serviceName = service.scalar("name", "title", "displayName")
        val sourceType = service.scalar("type", "serviceType", "typeName")
        val descriptor = collectDescriptors(service).lowercase()
        val kind = detectKind(descriptor)
        val characteristics = service.arrayValue("characteristics")
            ?: findArray(service, "characteristics")
            ?: JsonArray(emptyList())
        val parsed = characteristics.mapNotNull { it as? JsonObject }
            .mapIndexed { index, characteristic -> parseCharacteristic(characteristic, index) }

        val toggle = parsed.firstOrNull { it.role == CharacteristicRole.TOGGLE && it.writable }
        val range = parsed.firstOrNull { it.role == CharacteristicRole.RANGE && it.writable }
        val button = parsed.firstOrNull { it.role == CharacteristicRole.BUTTON && it.writable }
        val title = accessoryName
        val subtitle = serviceName.takeIf { it.isNotBlank() && it != accessoryName }.orEmpty()

        if (toggle != null && range != null && kind in setOf(DeviceKind.LIGHT, DeviceKind.FAN, DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER)) {
            return listOf(
                SprutControl(
                    id = "$accessoryId:$serviceId:main",
                    accessoryId = accessoryId,
                    serviceId = serviceId,
                    characteristicId = toggle.id,
                    rangeCharacteristicId = range.id,
                    title = title,
                    subtitle = subtitle,
                    room = room,
                    kind = kind,
                    behavior = ControlBehavior.TOGGLE_RANGE,
                    value = SprutValue(
                        boolValue = toggle.value.asBoolean(),
                        numberValue = range.value.asDouble(),
                    ),
                    minimum = range.minimum,
                    maximum = range.maximum,
                    step = range.step,
                    unit = range.unit,
                    sourceType = sourceType,
                    valueField = toggle.valueField,
                    rangeValueField = range.valueField,
                ),
            )
        }

        val actions = buildList {
            toggle?.let {
                add(it.toControl(accessoryId, serviceId, title, subtitle, room, kind, sourceType, ControlBehavior.TOGGLE))
            }
            if (toggle == null) {
                range?.let {
                    add(it.toControl(accessoryId, serviceId, title, subtitle, room, kind, sourceType, ControlBehavior.RANGE))
                }
            }
            button?.let {
                add(it.toControl(accessoryId, serviceId, title, subtitle, room, kind, sourceType, ControlBehavior.BUTTON))
            }
        }
        return actions.distinctBy(SprutControl::id)
    }

    private fun ParsedCharacteristic.toControl(
        accessoryId: String,
        serviceId: String,
        title: String,
        subtitle: String,
        room: String,
        kind: DeviceKind,
        sourceType: String,
        behavior: ControlBehavior,
    ) = SprutControl(
        id = "$accessoryId:$serviceId:$id",
        accessoryId = accessoryId,
        serviceId = serviceId,
        characteristicId = id,
        title = title,
        subtitle = subtitle,
        room = room,
        kind = kind,
        behavior = behavior,
        value = value,
        minimum = minimum,
        maximum = maximum,
        step = step,
        unit = unit,
        writable = writable,
        sourceType = sourceType,
        valueField = valueField,
        rangeValueField = valueField,
    )

    private fun parseCharacteristic(characteristic: JsonObject, index: Int): ParsedCharacteristic {
        val id = characteristic.scalar("id", "cId", "index").ifBlank { index.toString() }
        val descriptor = collectDescriptors(characteristic).lowercase()
        val value = extractValue(characteristic)
        val field = extractValueField(characteristic)
        val role = when {
            BUTTON_MARKERS.any(descriptor::contains) -> CharacteristicRole.BUTTON
            RANGE_MARKERS.any(descriptor::contains) || field in NUMBER_FIELDS -> CharacteristicRole.RANGE
            TOGGLE_MARKERS.any(descriptor::contains) || field == "boolValue" -> CharacteristicRole.TOGGLE
            else -> CharacteristicRole.SENSOR
        }
        val readOnly = findBoolean(characteristic, "readOnly") == true
        return ParsedCharacteristic(
            id = id,
            role = role,
            value = value,
            valueField = field,
            minimum = findNumber(characteristic, "minValue", "minimum", "min") ?: 0.0,
            maximum = findNumber(characteristic, "maxValue", "maximum", "max") ?: 100.0,
            step = findNumber(characteristic, "minStep", "step")?.takeIf { it > 0.0 } ?: 1.0,
            unit = findString(characteristic, "unit", "units").orEmpty(),
            writable = !readOnly && !descriptor.contains("read_only") && !descriptor.contains("readonly"),
        )
    }

    private fun parseScenarios(response: JsonElement): List<SprutControl> {
        val scenarios = findArray(response, "scenarios") ?: return emptyList()
        return scenarios.mapNotNull { it as? JsonObject }.mapIndexed { index, scenario ->
            val scenarioId = scenario.scalar("id", "index").ifBlank { index.toString() }
            SprutControl(
                id = "scenario:$scenarioId",
                accessoryId = "",
                serviceId = "scenario",
                characteristicId = scenarioId,
                title = scenario.scalar("name", "title").ifBlank { "Сценарий $scenarioId" },
                room = "Сценарии",
                kind = DeviceKind.SCENE,
                behavior = ControlBehavior.BUTTON,
                sourceType = "scenario",
            )
        }
    }

    private fun detectKind(descriptor: String): DeviceKind = when {
        descriptor.containsAny("light", "bulb", "lamp") -> DeviceKind.LIGHT
        descriptor.containsAny("outlet", "socket") -> DeviceKind.OUTLET
        descriptor.contains("fan") -> DeviceKind.FAN
        descriptor.contains("curtain") -> DeviceKind.CURTAIN
        descriptor.contains("blind") -> DeviceKind.BLINDS
        descriptor.contains("shutter") -> DeviceKind.SHUTTER
        descriptor.contains("lock") -> DeviceKind.LOCK
        descriptor.containsAny("thermostat", "climate", "heater") -> DeviceKind.THERMOSTAT
        descriptor.contains("garage") -> DeviceKind.GARAGE
        descriptor.contains("valve") -> DeviceKind.VALVE
        descriptor.containsAny("security", "alarm") -> DeviceKind.SECURITY
        descriptor.contains("switch") -> DeviceKind.SWITCH
        else -> DeviceKind.OTHER
    }

    private fun extractValue(element: JsonElement): SprutValue {
        val valueObject = findValueObject(element) ?: return SprutValue()
        val bool = valueObject["boolValue"]?.jsonPrimitive?.booleanOrNull
        val number = NUMBER_FIELDS.firstNotNullOfOrNull { key -> valueObject[key]?.jsonPrimitive?.doubleOrNull }
        val text = listOf("stringValue", "enumValue").firstNotNullOfOrNull { key ->
            valueObject[key]?.jsonPrimitive?.content
        }
        return SprutValue(boolValue = bool, numberValue = number, stringValue = text)
    }

    private fun extractValueField(element: JsonElement): String {
        val valueObject = findValueObject(element) ?: return "boolValue"
        return VALUE_FIELDS.firstOrNull(valueObject::containsKey) ?: "boolValue"
    }

    private fun findValueObject(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> {
            if (VALUE_FIELDS.any(element::containsKey)) element
            else element["value"]?.let(::findValueObject)
                ?: element["control"]?.let(::findValueObject)
                ?: element.values.firstNotNullOfOrNull(::findValueObject)
        }
        is JsonArray -> element.firstNotNullOfOrNull(::findValueObject)
        else -> null
    }

    private fun findArray(element: JsonElement, key: String): JsonArray? = when (element) {
        is JsonObject -> (element.entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray)
            ?: element.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }

    private fun findObject(element: JsonElement, key: String): JsonObject? = when (element) {
        is JsonObject -> (element.entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonObject)
            ?: element.values.firstNotNullOfOrNull { findObject(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findObject(it, key) }
        else -> null
    }

    private fun findString(element: JsonElement, vararg keys: String): String? = when (element) {
        is JsonObject -> keys.firstNotNullOfOrNull { key ->
            element.entries.firstOrNull { it.key.equals(key, true) }
                ?.value
                ?.let { (it as? JsonPrimitive)?.content }
        } ?: element.values.firstNotNullOfOrNull { findString(it, *keys) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, *keys) }
        else -> null
    }

    private fun findNumber(element: JsonElement, vararg keys: String): Double? = when (element) {
        is JsonObject -> keys.firstNotNullOfOrNull { key ->
            element.entries.firstOrNull { it.key.equals(key, true) }?.value?.let { value ->
                (value as? JsonPrimitive)?.doubleOrNull
            }
        } ?: element.values.firstNotNullOfOrNull { findNumber(it, *keys) }
        is JsonArray -> element.firstNotNullOfOrNull { findNumber(it, *keys) }
        else -> null
    }

    private fun findBoolean(element: JsonElement, key: String): Boolean? = when (element) {
        is JsonObject -> element.entries.firstOrNull { it.key.equals(key, true) }
            ?.value
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: element.values.firstNotNullOfOrNull { findBoolean(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findBoolean(it, key) }
        else -> null
    }

    private fun collectDescriptors(element: JsonElement): String = buildString {
        fun visit(current: JsonElement, depth: Int) {
            if (depth > 3) return
            when (current) {
                is JsonObject -> current.forEach { (key, value) ->
                    if (key in DESCRIPTOR_KEYS && value is JsonPrimitive) append(' ').append(value.content)
                    else if (key != "value" && key != "control") visit(value, depth + 1)
                }
                is JsonArray -> current.take(8).forEach { visit(it, depth + 1) }
                else -> Unit
            }
        }
        visit(element, 0)
    }

    private fun JsonObject.scalar(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { it.key.equals(name, true) }?.value?.let { (it as? JsonPrimitive)?.content }
    }.orEmpty()

    private fun JsonObject.arrayValue(name: String): JsonArray? =
        entries.firstOrNull { it.key.equals(name, true) }?.value as? JsonArray

    private fun JsonObject.objectValue(name: String): JsonObject? =
        entries.firstOrNull { it.key.equals(name, true) }?.value as? JsonObject

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

    private data class ParsedCharacteristic(
        val id: String,
        val role: CharacteristicRole,
        val value: SprutValue,
        val valueField: String,
        val minimum: Double,
        val maximum: Double,
        val step: Double,
        val unit: String,
        val writable: Boolean,
    )

    private enum class CharacteristicRole { TOGGLE, RANGE, BUTTON, SENSOR }

    private companion object {
        val NUMBER_FIELDS = listOf("intValue", "longValue", "floatValue", "doubleValue", "uintValue")
        val VALUE_FIELDS = listOf("boolValue") + NUMBER_FIELDS + listOf("stringValue", "enumValue")
        val DESCRIPTOR_KEYS = setOf("type", "name", "title", "description", "characteristicType", "serviceType", "typeName")
        val TOGGLE_MARKERS = listOf("c_on", "on_off", "power", "enabled", "active", "targetlock", "targetdoor")
        val RANGE_MARKERS = listOf("brightness", "position", "rotation", "speed", "targettemperature", "setpoint", "volume")
        val BUTTON_MARKERS = listOf("button", "execute", "run", "programmable", "stateless")
    }
}

data class CharacteristicUpdate(
    val accessoryId: String,
    val serviceId: String,
    val characteristicId: String,
    val value: SprutValue,
)
