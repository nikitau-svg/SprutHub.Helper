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

    fun parseUpdate(event: JsonElement): CharacteristicUpdate? = parseUpdates(event).firstOrNull()

    fun parseUpdates(event: JsonElement): List<CharacteristicUpdate> {
        val latestById = linkedMapOf<String, CharacteristicUpdate>()
        findCharacteristicUpdates(event).forEach { characteristic ->
            parseCharacteristicUpdate(characteristic)?.let { update ->
                latestById["${update.accessoryId}:${update.serviceId}:${update.characteristicId}"] = update
            }
        }
        return latestById.values.toList()
    }

    private fun parseCharacteristicUpdate(characteristic: JsonObject): CharacteristicUpdate? {
        val accessoryId = characteristic.scalar("aId", "accessoryId")
        val serviceId = characteristic.scalar("sId", "serviceId")
        val characteristicId = characteristic.scalar("cId", "characteristicId", "id")
        if (accessoryId.isBlank() || serviceId.isBlank() || characteristicId.isBlank()) return null
        if (findValueObject(characteristic) == null) return null
        return CharacteristicUpdate(
            accessoryId = accessoryId,
            serviceId = serviceId,
            characteristicId = characteristicId,
            value = extractValue(characteristic),
        )
    }

    private fun findCharacteristicUpdates(element: JsonElement): List<JsonObject> = when (element) {
        is JsonObject -> buildList {
            val hasIds = element.scalar("aId", "accessoryId").isNotBlank() &&
                element.scalar("sId", "serviceId").isNotBlank() &&
                element.scalar("cId", "characteristicId", "id").isNotBlank()
            if (hasIds && findValueObject(element) != null) add(element)
            element.values.forEach { addAll(findCharacteristicUpdates(it)) }
        }
        is JsonArray -> element.flatMap(::findCharacteristicUpdates)
        else -> emptyList()
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
        val descriptor = listOf(accessoryName, serviceName, sourceType, collectDescriptors(service))
            .joinToString(" ")
            .lowercase()
        if (READ_ONLY_SERVICE_MARKERS.any(descriptor::contains)) return emptyList()
        val kind = detectKind(descriptor)
        val characteristics = service.arrayValue("characteristics")
            ?: findArray(service, "characteristics")
            ?: JsonArray(emptyList())
        val parsed = characteristics.mapNotNull { it as? JsonObject }
            .mapIndexed { index, characteristic -> parseCharacteristic(characteristic, index) }

        val toggle = parsed
            .filter { it.role == CharacteristicRole.TOGGLE && it.writable }
            .maxByOrNull { it.togglePriority() }
        val range = parsed
            .filter { it.role == CharacteristicRole.RANGE && it.writable }
            .maxByOrNull { it.rangePriority(kind) }
        val button = parsed.firstOrNull { it.role == CharacteristicRole.BUTTON && it.writable }
        val title = accessoryName
        val subtitle = serviceName.takeIf { it.isNotBlank() && it != accessoryName }.orEmpty()

        val primaryActions = if (
            toggle != null && range != null &&
            kind in setOf(
                DeviceKind.LIGHT,
                DeviceKind.FAN,
                DeviceKind.CURTAIN,
                DeviceKind.BLINDS,
                DeviceKind.SHUTTER,
                DeviceKind.THERMOSTAT,
            )
        ) {
            listOf(
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
        } else {
            buildList {
                toggle?.let {
                    add(
                        it.toControl(
                            accessoryId,
                            serviceId,
                            title,
                            actionSubtitle(subtitle, it),
                            room,
                            kind,
                            sourceType,
                            ControlBehavior.TOGGLE,
                        ),
                    )
                }
                if (toggle == null) {
                    range?.let {
                        add(
                            it.toControl(
                                accessoryId,
                                serviceId,
                                title,
                                actionSubtitle(subtitle, it),
                                room,
                                kind,
                                sourceType,
                                ControlBehavior.RANGE,
                            ),
                        )
                    }
                }
                button?.let {
                    add(
                        it.toControl(
                            accessoryId,
                            serviceId,
                            title,
                            actionSubtitle(subtitle, it),
                            room,
                            kind,
                            sourceType,
                            ControlBehavior.BUTTON,
                        ),
                    )
                }
            }
        }
        val sensors = parsed
            .filter { it.hasValue && !it.writable && !it.isNameMetadata }
            .map { characteristic ->
                characteristic.toControl(
                    accessoryId,
                    serviceId,
                    title,
                    actionSubtitle(subtitle, characteristic),
                    room,
                    kind,
                    sourceType,
                    ControlBehavior.SENSOR,
                )
            }
        return (primaryActions + sensors).distinctBy(SprutControl::id)
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
        val displayName = characteristic.scalar("name", "title", "displayName", "description")
        val typeName = characteristic.scalar("type", "characteristicType", "typeName", "shortId")
        val value = extractValue(characteristic)
        val hasValue = findValueObject(characteristic) != null
        val field = extractValueField(characteristic)
        val role = when {
            descriptor.containsMarker(BUTTON_MARKERS) -> CharacteristicRole.BUTTON
            (descriptor.containsMarker(TOGGLE_MARKERS) && !descriptor.containsMarker(NON_TOGGLE_MARKERS)) ||
                field == "boolValue" -> CharacteristicRole.TOGGLE
            descriptor.containsMarker(RANGE_MARKERS) || field in NUMBER_FIELDS -> CharacteristicRole.RANGE
            else -> CharacteristicRole.SENSOR
        }
        val readOnly = findBoolean(characteristic, "readOnly") == true
        val explicitWrite = findBoolean(characteristic, "write")
        return ParsedCharacteristic(
            id = id,
            descriptor = descriptor,
            displayName = displayName,
            typeName = typeName,
            hasValue = hasValue,
            isNameMetadata = isNameType(typeName),
            role = role,
            value = value,
            valueField = field,
            minimum = findNumber(characteristic, "minValue", "minimum", "min") ?: 0.0,
            maximum = findNumber(characteristic, "maxValue", "maximum", "max") ?: 100.0,
            step = findNumber(characteristic, "minStep", "step")?.takeIf { it > 0.0 } ?: 1.0,
            unit = findString(characteristic, "unit", "units").orEmpty(),
            writable = explicitWrite ?: (
                !readOnly && !descriptor.contains("read_only") && !descriptor.contains("readonly")
                ),
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
        descriptor.containsAny("light", "bulb", "lamp", "свет", "ламп") -> DeviceKind.LIGHT
        descriptor.containsAny("outlet", "socket", "розет") -> DeviceKind.OUTLET
        descriptor.containsAny("fan", "вентил") -> DeviceKind.FAN
        descriptor.containsAny("curtain", "awning", "штор") -> DeviceKind.CURTAIN
        descriptor.containsAny("blind", "windowcovering", "window_covering", "roller", "жалюз") -> DeviceKind.BLINDS
        descriptor.containsAny("shutter", "ставн") -> DeviceKind.SHUTTER
        descriptor.containsAny("lock", "замок") -> DeviceKind.LOCK
        descriptor.containsAny(
            "thermostat",
            "climate",
            "heater",
            "airconditioner",
            "air_conditioner",
            "heatpump",
            "heat_pump",
            "hvac",
            "кондиционер",
            "термостат",
            "отоп",
        ) -> DeviceKind.THERMOSTAT
        descriptor.containsAny("garage", "гараж") -> DeviceKind.GARAGE
        descriptor.containsAny("valve", "sprinkler", "клапан", "полив") -> DeviceKind.VALVE
        descriptor.containsAny("security", "alarm", "сигнал") -> DeviceKind.SECURITY
        descriptor.containsAny("vacuum", "пылесос") -> DeviceKind.VACUUM
        descriptor.containsAny("television", "speaker", "телевизор") -> DeviceKind.TELEVISION
        descriptor.containsAny("switch", "переключ") -> DeviceKind.SWITCH
        descriptor.containsAny("sensor", "датчик") -> DeviceKind.SENSOR
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
                    else if (key != "value") visit(value, depth + 1)
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

    private fun String.containsMarker(markers: List<String>): Boolean {
        val normalized = filter(Char::isLetterOrDigit)
        return markers.any { marker ->
            contains(marker) || normalized.contains(marker.filter(Char::isLetterOrDigit))
        }
    }

    private fun actionSubtitle(serviceName: String, characteristic: ParsedCharacteristic): String {
        val characteristicName = characteristic.displayName
            .takeIf(String::isNotBlank)
            ?: readableIdentifier(characteristic.typeName)
        return listOf(serviceName, characteristicName)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase().filter(Char::isLetterOrDigit) }
            .joinToString(" · ")
    }

    private fun readableIdentifier(identifier: String): String {
        if (identifier.isBlank()) return ""
        val normalized = identifier
            .replace(Regex("^(HS|HC)[.:_]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[SC]_", RegexOption.IGNORE_CASE), "")
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("(?<=[a-zа-я0-9])(?=[A-ZА-Я])"), " ")
            .trim()
        return normalized.replaceFirstChar { it.uppercase() }
    }

    private fun isNameType(identifier: String): Boolean {
        val normalized = identifier.lowercase().filter(Char::isLetterOrDigit)
        return normalized in setOf("name", "cname", "characteristicname")
    }

    private fun ParsedCharacteristic.togglePriority(): Int = when {
        descriptor.containsMarker(listOf("c_on", "on_off", "power")) -> 300
        descriptor.containsMarker(listOf("c_active", "enabled")) -> 250
        descriptor.containsMarker(listOf("targetlock", "targetdoor")) -> 220
        valueField == "boolValue" -> 150
        else -> 50
    }

    private fun ParsedCharacteristic.rangePriority(kind: DeviceKind): Int = when {
        kind == DeviceKind.THERMOSTAT && descriptor.containsMarker(THERMOSTAT_RANGE_MARKERS) -> 400
        kind == DeviceKind.LIGHT && descriptor.containsMarker(listOf("brightness")) -> 400
        kind == DeviceKind.FAN && descriptor.containsMarker(listOf("rotation", "speed")) -> 400
        kind in setOf(DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER) &&
            descriptor.containsMarker(listOf("targetposition", "position")) -> 400
        descriptor.containsMarker(RANGE_MARKERS) -> 200
        else -> 50
    }

    private data class ParsedCharacteristic(
        val id: String,
        val descriptor: String,
        val displayName: String,
        val typeName: String,
        val hasValue: Boolean,
        val isNameMetadata: Boolean,
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
        val READ_ONLY_SERVICE_MARKERS = listOf(
            "accessoryinformation",
            "accessory_information",
            "s_accessory_information",
        )
        val TOGGLE_MARKERS = listOf(
            "c_on",
            "on_off",
            "power",
            "enabled",
            "active",
            "targetlock",
            "targetdoor",
        )
        val NON_TOGGLE_MARKERS = listOf("activeidentifier", "active_identifier")
        val THERMOSTAT_RANGE_MARKERS = listOf(
            "targettemperature",
            "coolingthresholdtemperature",
            "heatingthresholdtemperature",
            "setpoint",
        )
        val RANGE_MARKERS = listOf(
            "brightness",
            "position",
            "rotation",
            "speed",
            "targettemperature",
            "coolingthresholdtemperature",
            "heatingthresholdtemperature",
            "setpoint",
            "volume",
        )
        val BUTTON_MARKERS = listOf("button", "execute", "run", "programmable", "stateless")
    }
}

data class CharacteristicUpdate(
    val accessoryId: String,
    val serviceId: String,
    val characteristicId: String,
    val value: SprutValue,
)
