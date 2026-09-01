package io.github.nikitau.spruthubhelper.sprut

import android.util.Log
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.presence.DISTANCE_KEY
import io.github.nikitau.spruthubhelper.presence.PRESENCE_KEY
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Creates one native SprutHub OccupancySensor accessory per Android geofence. */
class VirtualPresenceDeviceManager(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
) {
    suspend fun createOrRecover(zone: PresenceZone): HealthDeviceBinding {
        val config = settings.currentConfig()
        client.connect(config)
        val name = zone.binding?.name?.takeIf(String::isNotBlank) ?: deviceName(zone)
        val accessories = listExpandedAccessories(config)
        val existingById = zone.binding?.accessoryId?.takeIf(String::isNotBlank)?.let { storedId ->
            accessories.firstOrNull { accessory -> accessory.scalar("id", "aId") == storedId }
        }
        check(existingById?.boolean("virtual") != false) {
            "Защитная проверка остановила привязку зоны: сохранённый аксессуар больше не виртуальный"
        }
        val existing = existingById ?: findPresenceAccessory(accessories, name, zone)
        if (existing != null) {
            return awaitBinding(config, existing.scalar("id", "aId"), name, zone)
        }

        val serviceTypesResponse = client.call(config, request("service", "types"))
        val serviceTypes = findArray(serviceTypesResponse, "types")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
        val occupancyType = serviceTypes.firstOrNull { type ->
            type.identifiers().any { it.normalizedType() == OCCUPANCY_SERVICE.normalizedType() }
        } ?: error("В этой версии SprutHub нет сервиса OccupancySensor")
        val occupancyTypeId = occupancyType.scalar("type", "id", "shortId")
            .takeIf(String::isNotBlank)
            ?: OCCUPANCY_SERVICE
        val distanceType = occupancyType.array("optional")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { type ->
                type.identifiers().any { identifier -> identifier.isDistanceType() }
            }
            ?.scalar("type", "id", "shortId")
            ?.takeIf(String::isNotBlank)

        if (zone.publishDistance && distanceType == null) {
            error("Эта версия SprutHub не разрешает C_Distance у датчика присутствия")
        }

        val response = client.call(
            config,
            request(
                "accessory",
                "create",
                buildJsonObject {
                    put("name", name)
                    put("roomId", idValue(zone.roomId))
                    put("expand", EXPAND_PRIMARY)
                    put("services", buildJsonArray {
                        add(buildJsonObject {
                            put("type", occupancyTypeId)
                            put("name", zone.name)
                            if (zone.publishDistance && distanceType != null) {
                                put("optional", buildJsonArray { add(JsonPrimitive(distanceType)) })
                            }
                        })
                    })
                },
            ),
        )
        val id = findAccessoryId(response).orEmpty()
        return awaitBinding(config, id, name, zone)
    }

    suspend fun publish(
        zone: PresenceZone,
        inside: Boolean,
        distanceMeters: Double?,
    ): HealthDeviceBinding {
        val binding = zone.binding ?: createOrRecover(zone)
        val config = settings.currentConfig()
        client.connect(config)
        val live = awaitBinding(config, binding.accessoryId, binding.name, zone.copy(binding = binding))
        val expected = linkedMapOf<String, JsonPrimitive>()

        live.targets.firstOrNull { it.key == PRESENCE_KEY }
            ?.let { target ->
                val value = target.encodedBoolean(inside)
                update(config, live.accessoryId, target, value)
                expected[PRESENCE_KEY] = value
            }
            ?: error("SprutHub не вернул характеристику присутствия")

        if (zone.publishDistance && distanceMeters != null) {
            live.targets.firstOrNull { it.key == DISTANCE_KEY }
                ?.let { target ->
                    val value = target.encodedNumber(distanceMeters.coerceAtLeast(0.0))
                    update(config, live.accessoryId, target, value)
                    expected[DISTANCE_KEY] = value
                }
                ?: error("SprutHub не вернул характеристику расстояния")
        }

        verify(config, live, expected)
        return live
    }

    suspend fun recreate(zone: PresenceZone): HealthDeviceBinding {
        delete(zone)
        return createOrRecover(zone.copy(binding = null))
    }

    suspend fun delete(zone: PresenceZone) {
        val binding = zone.binding ?: return
        val config = settings.currentConfig()
        client.connect(config)
        val accessory = listExpandedAccessories(config).firstOrNull {
            it.scalar("id", "aId") == binding.accessoryId
        } ?: return
        check(accessory.boolean("virtual") != false) {
            "Защитная проверка остановила удаление: аксессуар больше не виртуальный"
        }
        check(sameSprutLabel(accessory.scalar("name", "title", "displayName"), binding.name)) {
            "Защитная проверка остановила удаление: имя аксессуара изменилось"
        }
        client.call(
            config,
            request(
                "accessory",
                "delete",
                buildJsonObject { put("id", idValue(binding.accessoryId)) },
            ),
        )
        repeat(DELETE_ATTEMPTS) { attempt ->
            if (listExpandedAccessories(config).none { it.scalar("id", "aId") == binding.accessoryId }) return
            if (attempt < DELETE_ATTEMPTS - 1) delay(RETRY_MS)
        }
        error("SprutHub принял удаление зоны, но аксессуар всё ещё существует")
    }

    private suspend fun awaitBinding(
        config: HubConfig,
        accessoryId: String,
        name: String,
        zone: PresenceZone,
    ): HealthDeviceBinding {
        var lastProblem = "аксессуар ещё не появился"
        repeat(BINDING_ATTEMPTS) { attempt ->
            val accessories = listExpandedAccessories(config)
            val accessoryById = accessoryId.takeIf(String::isNotBlank)?.let { storedId ->
                accessories.firstOrNull { candidate -> candidate.scalar("id", "aId") == storedId }
            }
            check(accessoryById?.boolean("virtual") != false) {
                "Защитная проверка остановила привязку зоны: аксессуар больше не виртуальный"
            }
            val accessory = accessoryById ?: findPresenceAccessory(accessories, name, zone)
            if (accessory != null) {
                bindingFromAccessory(accessory, name, zone)?.let { return it }
                lastProblem = "характеристики присутствия ещё не готовы"
            }
            if (attempt < BINDING_ATTEMPTS - 1) delay(RETRY_MS)
        }
        error("Устройство зоны не готово: $lastProblem")
    }

    private fun findPresenceAccessory(
        accessories: List<JsonObject>,
        name: String,
        zone: PresenceZone,
    ): JsonObject? {
        val matches = accessories.filter { accessory ->
            accessory.boolean("virtual") != false &&
                sameSprutLabel(accessory.scalar("name", "title", "displayName"), name)
        }
        if (matches.isEmpty()) return null
        val expected = buildSet {
            add(PRESENCE_KEY)
            if (zone.publishDistance) add(DISTANCE_KEY)
        }
        val candidates = matches.map { accessory ->
            VirtualAccessoryCandidate(
                id = accessory.scalar("id", "aId"),
                fieldTitles = presenceFields(accessory),
            )
        }
        val selectedId = selectVirtualAccessoryId(candidates, expected) ?: return null
        return matches.firstOrNull { it.scalar("id", "aId") == selectedId }
    }

    private fun presenceFields(accessory: JsonObject): Set<String> {
        val characteristics = accessory.array("services")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .flatMap { service -> service.array("characteristics").orEmpty().mapNotNull { it as? JsonObject } }
        return buildSet {
            if (characteristics.any { characteristic ->
                    characteristic.identifiers().any { it.isOccupancyType() }
                }
            ) add(PRESENCE_KEY)
            if (characteristics.any { characteristic ->
                    characteristic.identifiers().any { it.isDistanceType() }
                }
            ) add(DISTANCE_KEY)
        }
    }

    private fun bindingFromAccessory(
        accessory: JsonObject,
        name: String,
        zone: PresenceZone,
    ): HealthDeviceBinding? {
        val accessoryId = accessory.scalar("id", "aId").takeIf(String::isNotBlank) ?: return null
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val service = services.firstOrNull { candidate ->
            candidate.identifiers().any { it.normalizedType() == OCCUPANCY_SERVICE.normalizedType() }
        } ?: services.singleOrNull() ?: return null
        val serviceId = service.scalar("sId", "id").takeIf(String::isNotBlank) ?: return null
        val characteristics = service.array("characteristics").orEmpty().mapNotNull { it as? JsonObject }
        val dataCharacteristics = characteristics.filterNot { it.identifiers().any(::isSprutNameTypeIdentifier) }

        val occupancy = dataCharacteristics.firstOrNull { characteristic ->
            characteristic.identifiers().any { it.isOccupancyType() }
        } ?: dataCharacteristics.firstOrNull { findValueField(it) in INTEGER_FIELDS }
            ?: return null
        val occupancyId = occupancy.scalar("cId", "id").takeIf(String::isNotBlank) ?: return null
        val occupancyField = findValueField(occupancy) ?: "intValue"

        val targets = buildList {
            add(HealthTarget(PRESENCE_KEY, serviceId, occupancyId, occupancyField))
            if (zone.publishDistance) {
                val distance = dataCharacteristics.firstOrNull { characteristic ->
                    characteristic.identifiers().any { it.isDistanceType() }
                } ?: dataCharacteristics.firstOrNull { candidate ->
                    candidate !== occupancy && findValueField(candidate) in DECIMAL_FIELDS
                } ?: return null
                val distanceId = distance.scalar("cId", "id").takeIf(String::isNotBlank) ?: return null
                add(
                    HealthTarget(
                        DISTANCE_KEY,
                        serviceId,
                        distanceId,
                        findValueField(distance) ?: "doubleValue",
                    ),
                )
            }
        }
        return HealthDeviceBinding(
            accessoryId = accessoryId,
            name = name,
            roomId = accessory.scalar("roomId", "rId").ifBlank { zone.roomId },
            targets = targets,
        )
    }

    private suspend fun update(
        config: HubConfig,
        accessoryId: String,
        target: HealthTarget,
        value: JsonPrimitive,
    ) {
        client.call(
            config,
            request(
                "characteristic",
                "update",
                buildJsonObject {
                    put("aId", idValue(accessoryId))
                    put("sId", idValue(target.serviceId))
                    put("cId", idValue(target.characteristicId))
                    put("control", buildJsonObject {
                        put("value", buildJsonObject { put(target.valueField, value) })
                    })
                },
            ),
        )
    }

    private suspend fun verify(
        config: HubConfig,
        binding: HealthDeviceBinding,
        expected: Map<String, JsonPrimitive>,
    ) {
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(VERIFY_RETRY_MS)
            val accessory = listExpandedAccessories(config).firstOrNull {
                it.scalar("id", "aId") == binding.accessoryId
            }
            val mismatches = expected.filter { (key, expectedValue) ->
                val target = binding.targets.firstOrNull { it.key == key } ?: return@filter true
                val actual = accessory?.valueFor(target) ?: return@filter true
                !valuesEqual(expectedValue, actual)
            }
            if (mismatches.isEmpty()) return
        }
        error("SprutHub принял значения зоны, но не подтвердил их чтением")
    }

    private suspend fun listExpandedAccessories(config: HubConfig): List<JsonObject> {
        var last = emptyList<JsonObject>()
        var lastError: Throwable? = null
        for (expand in listOf(EXPAND_PRIMARY, EXPAND_LEGACY)) {
            val result = runCatching {
                client.call(
                    config,
                    request("accessory", "list", buildJsonObject { put("expand", expand) }),
                )
            }
            result.onFailure { lastError = it }
            val accessories = result.getOrNull()?.let(::findAccessories).orEmpty()
            if (accessories.isNotEmpty()) last = accessories
            if (accessories.any { accessory ->
                    accessory.array("services").orEmpty().any { service ->
                        (service as? JsonObject)?.array("characteristics").isNullOrEmpty().not()
                    }
                }
            ) return accessories
        }
        if (last.isNotEmpty()) return last
        throw lastError ?: IllegalStateException("SprutHub не вернул список аксессуаров")
    }

    private fun JsonObject.valueFor(target: HealthTarget): JsonPrimitive? {
        val service = array("services").orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.scalar("sId", "id") == target.serviceId }
            ?: return null
        val characteristic = service.array("characteristics").orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.scalar("cId", "id") == target.characteristicId }
            ?: return null
        return findPrimitive(characteristic, target.valueField)
    }

    private fun findPrimitive(element: JsonElement, field: String): JsonPrimitive? = when (element) {
        is JsonObject -> (element[field] as? JsonPrimitive)
            ?: element["value"]?.let { findPrimitive(it, field) }
            ?: element["control"]?.let { findPrimitive(it, field) }
        else -> null
    }

    private fun findValueField(element: JsonElement): String? = when (element) {
        is JsonObject -> VALUE_FIELDS.firstOrNull(element::containsKey)
            ?: element["value"]?.let(::findValueField)
            ?: element["control"]?.let(::findValueField)
            ?: element.values.firstNotNullOfOrNull(::findValueField)
        is JsonArray -> element.firstNotNullOfOrNull(::findValueField)
        else -> null
    }

    private fun findAccessories(element: JsonElement): List<JsonObject> {
        val named = findArray(element, "accessories")?.mapNotNull { it as? JsonObject }.orEmpty()
        if (named.isNotEmpty()) return named
        return when (element) {
            is JsonObject -> buildList {
                if (element.scalar("id", "aId").isNotBlank() && element.array("services") != null) add(element)
                element.values.forEach { addAll(findAccessories(it)) }
            }.distinctBy { it.scalar("id", "aId") }
            is JsonArray -> element.flatMap(::findAccessories).distinctBy { it.scalar("id", "aId") }
            else -> emptyList()
        }
    }

    private fun findAccessoryId(element: JsonElement): String? = when (element) {
        is JsonObject -> element.scalar("aId", "accessoryId", "id").takeIf(String::isNotBlank)
            ?: element.values.firstNotNullOfOrNull(::findAccessoryId)
        is JsonArray -> element.firstNotNullOfOrNull(::findAccessoryId)
        else -> null
    }

    private fun findArray(element: JsonElement, key: String): JsonArray? = when (element) {
        is JsonObject -> element.entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray
            ?: element.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }

    private fun JsonObject.identifiers(): List<String> = buildList {
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key.lowercase() in TYPE_KEYS) {
                        (value as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    collect(value)
                }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(this@identifiers)
    }.distinct()

    private fun String.normalizedType(): String = lowercase().filter(Char::isLetterOrDigit)
    private fun String.isOccupancyType(): Boolean = normalizedType().contains("occupancydetected")
    private fun String.isDistanceType(): Boolean = normalizedType() in setOf("cdistance", "distance") ||
        normalizedType().contains("distancesensor")

    private fun valuesEqual(first: JsonPrimitive, second: JsonPrimitive): Boolean {
        val firstNumber = first.content.toDoubleOrNull()
        val secondNumber = second.content.toDoubleOrNull()
        return if (firstNumber != null && secondNumber != null) abs(firstNumber - secondNumber) < 0.01
        else first.content == second.content
    }

    private fun HealthTarget.encodedBoolean(value: Boolean): JsonPrimitive = when (valueField) {
        "boolValue" -> JsonPrimitive(value)
        "floatValue", "doubleValue" -> JsonPrimitive(if (value) 1.0 else 0.0)
        "stringValue", "enumValue" -> JsonPrimitive(if (value) "1" else "0")
        else -> JsonPrimitive(if (value) 1 else 0)
    }

    private fun HealthTarget.encodedNumber(value: Double): JsonPrimitive = when (valueField) {
        "intValue", "longValue", "uintValue" -> JsonPrimitive(value.roundToLong())
        "stringValue", "enumValue" -> JsonPrimitive(value.toString())
        else -> JsonPrimitive(value)
    }

    private fun deviceName(zone: PresenceZone): String =
        "Присутствие · ${zone.name.trim()} · ${zone.id.replace("-", "").take(6)}"

    private fun request(section: String, operation: String, body: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject { put(section, buildJsonObject { put(operation, body) }) }

    private fun idValue(value: String): JsonPrimitive =
        value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)

    private fun JsonObject.scalar(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, true) }?.value?.let { (it as? JsonPrimitive)?.content }
    }.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean? = entries
        .firstOrNull { it.key.equals(key, true) }
        ?.value
        ?.let { runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull() }

    private fun JsonObject.array(key: String): JsonArray? =
        entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray

    private companion object {
        const val LOG_TAG = "SprutHubPresence"
        const val OCCUPANCY_SERVICE = "OccupancySensor"
        const val EXPAND_PRIMARY = "services,characteristics"
        const val EXPAND_LEGACY = "services+characteristics"
        const val BINDING_ATTEMPTS = 10
        const val DELETE_ATTEMPTS = 10
        const val RETRY_MS = 400L
        const val VERIFY_ATTEMPTS = 4
        const val VERIFY_RETRY_MS = 300L
        val INTEGER_FIELDS = setOf("intValue", "longValue", "uintValue", "boolValue")
        val DECIMAL_FIELDS = setOf("doubleValue", "floatValue", "intValue", "longValue", "uintValue")
        val VALUE_FIELDS = listOf(
            "boolValue",
            "intValue",
            "longValue",
            "uintValue",
            "floatValue",
            "doubleValue",
            "stringValue",
            "enumValue",
        )
        val TYPE_KEYS = setOf("type", "id", "shortid", "characteristictype", "typename", "format")
    }
}
