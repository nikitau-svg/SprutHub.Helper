package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.DeviceKind
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import io.github.nikitau.spruthubhelper.data.SprutControl
import io.github.nikitau.spruthubhelper.data.SprutRoom
import io.github.nikitau.spruthubhelper.data.SprutValue
import io.github.nikitau.spruthubhelper.data.SprutValueOption
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

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
                    name = room.displayScalar("name", "title").ifBlank { "Без комнаты" },
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
        val accessoryName = accessory.displayScalar("name", "title", "displayName")
            .ifBlank { "Устройство $accessoryId" }
        val roomId = accessory.scalar("roomId", "rId", "room")
        val roomName = rooms[roomId]
            ?: accessory.objectValue("room")?.displayScalar("name", "title")
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
        val serviceName = service.displayScalar("name", "title", "displayName", "overview")
        val sourceType = service.scalar("type", "serviceType", "typeName")
        val normalizedSourceType = normalizeCatalogIdentifier(sourceType)
        val servicePrimary = service.booleanScalar("primary") ?: false
        val linkedServiceIds = service.arrayValue("linkedServices")
            .orEmpty()
            .mapNotNull(::serviceReferenceId)
            .filterNot { it == serviceId }
            .distinct()
        val descriptor = listOf(accessoryName, serviceName, sourceType, collectDescriptors(service))
            .joinToString(" ")
            .lowercase()
        if (
            normalizedSourceType in IGNORED_SERVICE_TYPES ||
            normalizedSourceType.startsWith("camera") ||
            READ_ONLY_SERVICE_MARKERS.any(descriptor::contains)
        ) return emptyList()
        val kind = detectKind(normalizedSourceType, descriptor)
        val characteristics = service.arrayValue("characteristics")
            ?: findArray(service, "characteristics")
            ?: JsonArray(emptyList())
        val parsed = characteristics.mapNotNull { it as? JsonObject }
            .mapIndexed { index, characteristic -> parseCharacteristic(characteristic, index) }
        val thermostatMode = parsed.thermostatMode()

        val toggle = parsed
            .filter { it.role == CharacteristicRole.TOGGLE && it.writable }
            .maxByOrNull { it.togglePriority() }
        val range = parsed
            .filter { it.role == CharacteristicRole.RANGE && it.writable }
            .maxByOrNull { it.rangePriority(kind, thermostatMode) }
        val options = parsed.filter { it.role == CharacteristicRole.OPTIONS && it.writable }
        val button = parsed.firstOrNull { it.role == CharacteristicRole.BUTTON && it.writable }
        val title = accessoryName
        val subtitle = serviceName.takeIf { it.isNotBlank() && it != accessoryName }.orEmpty()

        val baseActions = if (
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
                        boolValue = toggle.value.asBooleanOrNull(),
                        numberValue = range.value.numberValue,
                    ),
                    minimum = range.minimum,
                    maximum = range.maximum,
                    step = range.step,
                    unit = range.unit,
                    sourceType = sourceType,
                    serviceName = serviceName,
                    characteristicType = toggle.typeName,
                    characteristicName = toggle.displayName,
                    rangeCharacteristicType = range.typeName,
                    servicePrimary = servicePrimary,
                    linkedServiceIds = linkedServiceIds,
                    valueOptions = toggle.valueOptions,
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
                            serviceName,
                            servicePrimary,
                            linkedServiceIds,
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
                                serviceName,
                                servicePrimary,
                                linkedServiceIds,
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
                            serviceName,
                            servicePrimary,
                            linkedServiceIds,
                            ControlBehavior.BUTTON,
                        ),
                    )
                }
            }
        }
        val primaryActions = baseActions + options.map { option ->
            option.toControl(
                accessoryId,
                serviceId,
                title,
                actionSubtitle(subtitle, option),
                room,
                kind,
                sourceType,
                serviceName,
                servicePrimary,
                linkedServiceIds,
                ControlBehavior.OPTIONS,
            )
        }
        val actionCharacteristicIds = primaryActions.flatMap { control ->
            listOfNotNull(control.characteristicId, control.rangeCharacteristicId)
        }.toSet()
        val attributes = parsed
            .filter { characteristic ->
                characteristic.hasValue &&
                    !characteristic.isNameMetadata &&
                    characteristic.id !in actionCharacteristicIds
            }
            .map { characteristic ->
                characteristic.toControl(
                    accessoryId,
                    serviceId,
                    title,
                    actionSubtitle(subtitle, characteristic),
                    room,
                    kind,
                    sourceType,
                    serviceName,
                    servicePrimary,
                    linkedServiceIds,
                    ControlBehavior.SENSOR,
                ).copy(writable = false)
            }
        return (primaryActions + attributes).distinctBy(SprutControl::id)
    }

    private fun ParsedCharacteristic.toControl(
        accessoryId: String,
        serviceId: String,
        title: String,
        subtitle: String,
        room: String,
        kind: DeviceKind,
        sourceType: String,
        serviceName: String,
        servicePrimary: Boolean,
        linkedServiceIds: List<String>,
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
        serviceName = serviceName,
        characteristicType = typeName,
        characteristicName = displayName,
        servicePrimary = servicePrimary,
        linkedServiceIds = linkedServiceIds,
        valueOptions = valueOptions,
        valueField = valueField,
        rangeValueField = valueField,
    )

    private fun parseCharacteristic(characteristic: JsonObject, index: Int): ParsedCharacteristic {
        val id = characteristic.scalar("id", "cId", "index").ifBlank { index.toString() }
        val descriptor = collectDescriptors(characteristic).lowercase()
        val displayName = characteristic.displayScalar("name", "title", "displayName", "description", "overview")
            .ifBlank { findString(characteristic, "displayName", "name", "title", "description").orEmpty() }
        val discoveredType = characteristic.scalar("type", "characteristicType", "typeName", "shortId")
            .ifBlank { findString(characteristic, "characteristicType", "typeName", "shortId", "type").orEmpty() }
        val typeName = discoveredType
            .takeUnless { it.lowercase() in GENERIC_VALUE_TYPES }
            .orEmpty()
            .ifBlank { inferCharacteristicType(descriptor) }
        val value = extractValue(characteristic)
        val hasValue = findValueObject(characteristic) != null
        val field = extractValueField(characteristic)
        val readOnly = findBoolean(characteristic, "readOnly") == true
        val explicitWrite = findBoolean(characteristic, "write")
        val writable = explicitWrite ?: (
            !readOnly && !descriptor.contains("read_only") && !descriptor.contains("readonly")
            )
        val valueOptions = parseValueOptions(characteristic, field)
        val role = when {
            descriptor.containsMarker(BUTTON_MARKERS) -> CharacteristicRole.BUTTON
            (descriptor.containsMarker(TOGGLE_MARKERS) && !descriptor.containsMarker(NON_TOGGLE_MARKERS)) ||
                field == "boolValue" -> CharacteristicRole.TOGGLE
            writable && valueOptions.size > 1 -> CharacteristicRole.OPTIONS
            descriptor.containsMarker(RANGE_MARKERS) || field in NUMBER_FIELDS -> CharacteristicRole.RANGE
            else -> CharacteristicRole.SENSOR
        }
        return ParsedCharacteristic(
            id = id,
            descriptor = descriptor,
            displayName = displayName,
            typeName = typeName,
            hasValue = hasValue,
            isNameMetadata = isNameType(typeName),
            role = role,
            value = value,
            valueOptions = valueOptions,
            valueField = field,
            minimum = findNumber(characteristic, "minValue", "minimum", "min") ?: 0.0,
            maximum = findNumber(characteristic, "maxValue", "maximum", "max") ?: 100.0,
            step = findNumber(characteristic, "minStep", "step")?.takeIf { it > 0.0 } ?: 1.0,
            unit = findString(characteristic, "unit", "units").orEmpty(),
            writable = writable,
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
                title = scenario.displayScalar("name", "title").ifBlank { "Сценарий $scenarioId" },
                room = "Сценарии",
                kind = DeviceKind.SCENE,
                behavior = ControlBehavior.BUTTON,
                sourceType = "scenario",
            )
        }
    }

    private fun parseValueOptions(characteristic: JsonElement, valueField: String): List<SprutValueOption> =
        findArray(characteristic, "validValues")
            .orEmpty()
            .mapNotNull { option ->
                when (option) {
                    is JsonObject -> {
                        val value = findValueObject(option)?.let(::extractValue)
                            ?: option["value"]?.let { primitiveValue(it, valueField) }
                            ?: return@mapNotNull null
                        SprutValueOption(
                            value = value,
                            key = option.displayScalar("key", "id"),
                            name = option.displayScalar("name", "title", "displayName"),
                        )
                    }
                    is JsonPrimitive -> SprutValueOption(value = primitiveValue(option, valueField))
                    else -> null
                }
            }
            .distinctBy { option ->
                listOf(option.value.boolValue, option.value.numberValue, option.value.stringValue, option.key)
            }

    private fun primitiveValue(element: JsonElement, valueField: String): SprutValue {
        val primitive = element as? JsonPrimitive ?: return SprutValue()
        return when (valueField) {
            "boolValue" -> SprutValue(boolValue = primitive.booleanOrNull)
            in NUMBER_FIELDS -> SprutValue(numberValue = primitive.doubleOrNull)
            else -> SprutValue(stringValue = primitive.content)
        }
    }

    private fun detectKind(sourceType: String, descriptor: String): DeviceKind = when {
        sourceType in SENSOR_SERVICE_TYPES || sourceType.endsWith("sensor") || sourceType.endsWith("meter") ->
            DeviceKind.SENSOR
        sourceType == "lightbulb" -> DeviceKind.LIGHT
        sourceType == "outlet" -> DeviceKind.OUTLET
        sourceType in setOf("fan", "fanbasic", "airpurifier") -> DeviceKind.FAN
        sourceType in setOf("windowcovering", "slat") -> DeviceKind.BLINDS
        sourceType == "window" -> DeviceKind.CURTAIN
        sourceType == "door" -> DeviceKind.CURTAIN
        sourceType in setOf("lockmechanism", "lockmanagement") -> DeviceKind.LOCK
        sourceType in setOf("thermostat", "heatercooler", "humidifierdehumidifier", "temperaturecontrol") ->
            DeviceKind.THERMOSTAT
        sourceType == "garagedooropener" -> DeviceKind.GARAGE
        sourceType in setOf("valve", "faucet", "irrigationsystem") -> DeviceKind.VALVE
        sourceType == "securitysystem" -> DeviceKind.SECURITY
        sourceType == "vacuumcleaner" -> DeviceKind.VACUUM
        sourceType in setOf("television", "televisionspeaker", "speaker", "microphone", "inputsource") ->
            DeviceKind.TELEVISION
        sourceType in setOf(
            "switch",
            "statelessprogrammableswitch",
            "doorbell",
            "targetcontrol",
            "option",
            "massage",
            "petfeeder",
            "powermanagement",
        ) -> DeviceKind.SWITCH
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

    private fun normalizeCatalogIdentifier(identifier: String): String = identifier
        .trim()
        .replace(Regex("^(HS|HC|S|C)[._:-]", RegexOption.IGNORE_CASE), "")
        .lowercase()
        .filter(Char::isLetterOrDigit)

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
                ?.localizedText()
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
                    if (key in DESCRIPTOR_KEYS) value.localizedText()?.let { append(' ').append(it) }
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

    private fun JsonObject.displayScalar(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        entries.firstOrNull { it.key.equals(name, true) }?.value?.localizedText()
    }.orEmpty()

    private fun JsonElement.localizedText(): String? = when (this) {
        is JsonPrimitive -> content.takeIf(String::isNotBlank)
        is JsonObject -> {
            val locale = Locale.getDefault()
            val preferredKeys = listOf(locale.toLanguageTag(), locale.language, "ru", "en")
            preferredKeys.firstNotNullOfOrNull { preferred ->
                entries.firstOrNull { it.key.equals(preferred, ignoreCase = true) }
                    ?.value
                    ?.let { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
            } ?: values.firstNotNullOfOrNull { value ->
                (value as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
            }
        }
        else -> null
    }

    private fun serviceReferenceId(reference: JsonElement): String? = when (reference) {
        is JsonPrimitive -> reference.content.takeIf(String::isNotBlank)
        is JsonObject -> reference.scalar("id", "sId", "serviceId", "index").takeIf(String::isNotBlank)
        else -> null
    }

    private fun JsonObject.arrayValue(name: String): JsonArray? =
        entries.firstOrNull { it.key.equals(name, true) }?.value as? JsonArray

    private fun JsonObject.objectValue(name: String): JsonObject? =
        entries.firstOrNull { it.key.equals(name, true) }?.value as? JsonObject

    private fun JsonObject.booleanScalar(name: String): Boolean? =
        entries.firstOrNull { it.key.equals(name, true) }
            ?.value
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }

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

    private fun inferCharacteristicType(descriptor: String): String {
        val normalized = descriptor.lowercase().filter(Char::isLetterOrDigit)
        return INFERRED_CHARACTERISTIC_TYPES.firstOrNull(normalized::contains).orEmpty()
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

    private fun ParsedCharacteristic.rangePriority(kind: DeviceKind, thermostatMode: ThermostatMode?): Int = when {
        kind == DeviceKind.THERMOSTAT && descriptor.containsMarker(listOf("targettemperature")) -> 600
        kind == DeviceKind.THERMOSTAT && thermostatMode == ThermostatMode.COOL &&
            descriptor.containsMarker(listOf("coolingthresholdtemperature")) -> 580
        kind == DeviceKind.THERMOSTAT && thermostatMode == ThermostatMode.HEAT &&
            descriptor.containsMarker(listOf("heatingthresholdtemperature")) -> 580
        kind == DeviceKind.THERMOSTAT && descriptor.containsMarker(THERMOSTAT_RANGE_MARKERS) -> 500
        kind == DeviceKind.LIGHT && descriptor.containsMarker(listOf("brightness")) -> 400
        kind == DeviceKind.FAN && descriptor.containsMarker(listOf("rotation", "speed")) -> 400
        kind in setOf(DeviceKind.CURTAIN, DeviceKind.BLINDS, DeviceKind.SHUTTER) &&
            descriptor.containsMarker(listOf("targetposition", "position")) -> 400
        descriptor.containsMarker(RANGE_MARKERS) -> 200
        else -> 50
    }

    private fun List<ParsedCharacteristic>.thermostatMode(): ThermostatMode? {
        val target = firstOrNull { characteristic ->
            characteristic.descriptor.containsMarker(
                listOf("targetheatercoolerstate", "targetheatingcoolingstate"),
            )
        }
        val targetValue = target?.value?.numberValue?.toInt()
        if (target != null && targetValue != null) {
            return when {
                target.descriptor.containsMarker(listOf("targetheatercoolerstate")) -> when (targetValue) {
                    1 -> ThermostatMode.HEAT
                    2 -> ThermostatMode.COOL
                    0 -> ThermostatMode.AUTO
                    else -> null
                }
                else -> when (targetValue) {
                    1 -> ThermostatMode.HEAT
                    2 -> ThermostatMode.COOL
                    3 -> ThermostatMode.AUTO
                    else -> null
                }
            }
        }

        val current = firstOrNull { characteristic ->
            characteristic.descriptor.containsMarker(
                listOf("currentheatercoolerstate", "currentheatingcoolingstate"),
            )
        } ?: return null
        val currentValue = current.value.numberValue?.toInt() ?: return null
        return when {
            current.descriptor.containsMarker(listOf("currentheatercoolerstate")) -> when (currentValue) {
                2 -> ThermostatMode.HEAT
                3 -> ThermostatMode.COOL
                else -> null
            }
            else -> when (currentValue) {
                1 -> ThermostatMode.HEAT
                2 -> ThermostatMode.COOL
                else -> null
            }
        }
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
        val valueOptions: List<SprutValueOption>,
        val valueField: String,
        val minimum: Double,
        val maximum: Double,
        val step: Double,
        val unit: String,
        val writable: Boolean,
    )

    private enum class CharacteristicRole { TOGGLE, RANGE, OPTIONS, BUTTON, SENSOR }

    private enum class ThermostatMode { HEAT, COOL, AUTO }

    private companion object {
        val NUMBER_FIELDS = listOf("intValue", "longValue", "floatValue", "doubleValue", "uintValue")
        val VALUE_FIELDS = listOf("boolValue") + NUMBER_FIELDS + listOf("stringValue", "enumValue")
        val DESCRIPTOR_KEYS = setOf("type", "name", "title", "description", "characteristicType", "serviceType", "typeName")
        val READ_ONLY_SERVICE_MARKERS = listOf(
            "accessoryinformation",
            "accessory_information",
            "s_accessory_information",
        )
        val IGNORED_SERVICE_TYPES = setOf(
            "accesscontrol",
            "accessoryinformation",
            "audiostreammanagement",
            "camerartpstreammanagement",
            "cloudrelay",
            "datastreamtransportmanagement",
            "diagnostics",
            "happrotocolinformation",
            "servicelabel",
            "siri",
            "targetcontrolmanagement",
            "threadtransport",
            "transfertransportmanagement",
            "wifitransport",
        )
        val SENSOR_SERVICE_TYPES = setOf(
            "airqualitysensor",
            "batteryservice",
            "carbondioxidesensor",
            "carbonmonoxidesensor",
            "contactsensor",
            "filtermaintenance",
            "humiditysensor",
            "leaksensor",
            "lightsensor",
            "motionsensor",
            "occupancysensor",
            "smokesensor",
            "temperaturesensor",
            "accessoryextinfo",
            "atmosphericpressuresensor",
            "distancesensor",
            "gassensor",
            "noisesensor",
            "tiltangle",
            "ultravioletsensor",
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
        val INFERRED_CHARACTERISTIC_TYPES = listOf(
            "coolingthresholdtemperature",
            "heatingthresholdtemperature",
            "currentheatercoolerstate",
            "targetheatercoolerstate",
            "currentheatingcoolingstate",
            "targetheatingcoolingstate",
            "currentoperationalstate",
            "targetoperationalstate",
            "currentrelativehumidity",
            "targetrelativehumidity",
            "currenttemperature",
            "targettemperature",
            "currentposition",
            "targetposition",
            "positionstate",
            "currentfanstate",
            "targetfanstate",
            "rotationspeed",
            "fanspeed",
            "outletinuse",
            "statuslowbattery",
            "batterylevel",
            "statusfault",
            "statusjammed",
            "airquality",
            "brightness",
            "saturation",
            "active",
            "online",
            "inuse",
            "volume",
        )
        val GENERIC_VALUE_TYPES = setOf(
            "array",
            "bool",
            "boolean",
            "double",
            "enum",
            "float",
            "int",
            "integer",
            "long",
            "number",
            "object",
            "string",
            "uint",
        )
    }
}

data class CharacteristicUpdate(
    val accessoryId: String,
    val serviceId: String,
    val characteristicId: String,
    val value: SprutValue,
)
