package io.github.nikitau.spruthubhelper.sprut

import android.os.Build
import android.util.Log
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HealthValueKind
import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.health.HealthReading
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VirtualHealthDeviceManager(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
) {
    suspend fun createOrRecover(roomId: String): HealthDeviceBinding {
        val config = healthConfig()
        client.connect(config)
        val name = deviceName()
        val fields = virtualFields()
        Log.i(LOG_TAG, "Health device create/recover started")

        findHealthAccessory(listExpandedAccessories(config), name)?.let { existing ->
            val binding = awaitCompleteBinding(
                config = config,
                accessoryId = existing.scalar("id", "aId"),
                name = name,
                fallbackRoomId = roomId,
                expectedTypes = null,
                existing = true,
            )
            validateBinding(binding, fields, existing = true)
            Log.i(LOG_TAG, "Existing health device recovered with ${binding.targets.size} targets")
            return binding
        }

        val serviceTypes = client.call(config, request("service", "types"))
        val types = findArray(serviceTypes, "types").orEmpty().mapNotNull { it as? JsonObject }
        val optionService = types.firstOrNull { it.scalar("type", "id", "shortId") == OPTION_SERVICE_TYPE }
            ?: error("В этой версии SprutHub нет универсального сервиса C_Option")
        val characteristicTypes = optionService.array("optional").orEmpty().mapNotNull { it as? JsonObject }
        val selectedTypes = HealthValueKind.entries.associateWith { kind ->
            selectCharacteristicType(characteristicTypes, kind)
                ?: error("SprutHub не вернул характеристику типа ${kind.name}")
        }

        val createBody = buildJsonObject {
            put("name", name)
            put("roomId", idValue(roomId))
            put("expand", EXPAND_PRIMARY)
            put("services", buildJsonArray {
                fields.forEach { field ->
                    add(buildJsonObject {
                        put("type", OPTION_SERVICE_TYPE)
                        put("name", field.title)
                        put("optional", buildJsonArray {
                            add(JsonPrimitive(selectedTypes.getValue(field.kind)))
                        })
                    })
                }
            })
        }
        val createResponse = client.call(config, request("accessory", "create", createBody))
        val createdId = findAccessory(createResponse)?.scalar("id", "aId")
            ?.takeIf(String::isNotBlank)
            ?: findAccessoryId(createResponse).orEmpty()

        // accessory.create may return service stubs before real IDs are assigned.
        // Always re-read and poll accessory.list before persisting the binding.
        val binding = awaitCompleteBinding(
            config = config,
            accessoryId = createdId,
            name = name,
            fallbackRoomId = roomId,
            expectedTypes = selectedTypes,
            existing = false,
        )
        validateBinding(binding, fields, existing = false)
        Log.i(LOG_TAG, "Health device created with ${binding.targets.size} targets")
        return binding
    }

    suspend fun recoverExisting(): HealthDeviceBinding? {
        val config = healthConfig()
        client.connect(config)
        val name = deviceName()
        val accessory = findHealthAccessory(listExpandedAccessories(config), name) ?: return null
        val binding = awaitCompleteBinding(
            config = config,
            accessoryId = accessory.scalar("id", "aId"),
            name = name,
            fallbackRoomId = accessory.scalar("roomId", "rId"),
            expectedTypes = null,
            existing = true,
        )
        validateBinding(binding, virtualFields(), existing = true)
        Log.i(LOG_TAG, "Health binding repaired automatically")
        return binding
    }

    suspend fun publish(
        binding: HealthDeviceBinding,
        readings: Map<String, HealthReading>,
    ): HealthDeviceBinding {
        val config = healthConfig()
        client.connect(config)
        val liveBinding = awaitCompleteBinding(
            config = config,
            accessoryId = binding.accessoryId,
            name = binding.name,
            fallbackRoomId = binding.roomId,
            expectedTypes = null,
            existing = true,
            createdAtEpochMs = binding.createdAtEpochMs,
        )
        validateBinding(liveBinding, virtualFields(), existing = true)

        var published = 0
        val failures = mutableListOf<String>()
        val expectedValues = linkedMapOf<String, JsonPrimitive>()
        liveBinding.targets.forEach { target ->
            val reading = readings[target.key] ?: return@forEach
            val value = wireValue(target.valueField, reading) ?: return@forEach
            val body = buildJsonObject {
                put("aId", idValue(liveBinding.accessoryId))
                put("sId", idValue(target.serviceId))
                put("cId", idValue(target.characteristicId))
                put("control", buildJsonObject {
                    put("value", buildJsonObject { put(target.valueField, value) })
                })
            }
            runCatching {
                client.call(config, request("characteristic", "update", body))
            }.onSuccess {
                published += 1
                expectedValues[target.key] = value
            }.onFailure { error ->
                failures += "${target.key}: ${error.message.orEmpty().take(100)}"
                Log.e(LOG_TAG, "Health target ${target.key} failed", error)
            }
        }
        check(published > 0) { "Нет данных, подходящих для публикации в SprutHub" }
        check(failures.isEmpty()) {
            "Обновлено $published полей, не обновлено ${failures.size}: ${failures.joinToString().take(240)}"
        }
        verifyPublishedValues(config, liveBinding, expectedValues)
        Log.i(LOG_TAG, "Health sync published and verified $published fields")
        return liveBinding
    }

    private suspend fun awaitCompleteBinding(
        config: HubConfig,
        accessoryId: String,
        name: String,
        fallbackRoomId: String,
        expectedTypes: Map<HealthValueKind, String>?,
        existing: Boolean,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): HealthDeviceBinding {
        var lastProblem = "аксессуар ещё не появился в accessory.list"
        repeat(BINDING_ATTEMPTS) { attempt ->
            runCatching { listExpandedAccessories(config) }
                .onSuccess { accessories ->
                    val accessory = accessories.firstOrNull { candidate ->
                        (accessoryId.isNotBlank() && candidate.scalar("id", "aId") == accessoryId) ||
                            sameSprutLabel(candidate.scalar("name", "title", "displayName"), name)
                    }
                    if (accessory == null) {
                        lastProblem = "аксессуар не найден"
                    } else {
                        val roomId = accessory.scalar("roomId", "rId").ifBlank { fallbackRoomId }
                        val candidate = bindingFromAccessory(
                            accessory = accessory,
                            roomId = roomId,
                            name = name,
                            expectedTypes = expectedTypes,
                            createdAtEpochMs = createdAtEpochMs,
                        )
                        if (candidate != null) {
                            val missing = missingFields(candidate)
                            if (missing.isEmpty()) return candidate
                            lastProblem = "не распознано полей ${missing.size}"
                            Log.w(
                                LOG_TAG,
                                "Health binding incomplete: targets=${candidate.targets.size}, " +
                                    "missing=${missing.sorted().joinToString()}",
                            )
                        } else {
                            lastProblem = "структура характеристик ещё не распознана"
                            Log.w(LOG_TAG, bindingDiagnostic(accessory))
                        }
                    }
                }
                .onFailure { error -> lastProblem = error.message ?: "ошибка accessory.list" }
            if (attempt < BINDING_ATTEMPTS - 1) delay(BINDING_RETRY_MS)
        }
        val prefix = if (existing) "Существующее" else "Созданное"
        error("$prefix устройство здоровья не готово: $lastProblem")
    }

    private suspend fun listExpandedAccessories(config: HubConfig): List<JsonObject> {
        var lastAccessories = emptyList<JsonObject>()
        var lastError: Throwable? = null
        for (expand in listOf(EXPAND_PRIMARY, EXPAND_LEGACY)) {
            val result = runCatching {
                client.call(config, request("accessory", "list", buildJsonObject { put("expand", expand) }))
            }
            result.onFailure { lastError = it }
            val accessories = result.getOrNull()?.let(::findAccessories).orEmpty()
            if (accessories.isNotEmpty()) lastAccessories = accessories
            if (accessories.any(::hasExpandedCharacteristics)) return accessories
        }
        if (lastAccessories.isNotEmpty()) return lastAccessories
        throw lastError ?: IllegalStateException("SprutHub не вернул список аксессуаров")
    }

    private fun bindingFromAccessory(
        accessory: JsonObject,
        roomId: String,
        name: String,
        expectedTypes: Map<HealthValueKind, String>? = null,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ): HealthDeviceBinding? {
        val accessoryId = accessory.scalar("id", "aId")
        if (accessoryId.isBlank()) return null
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val fieldsByTitle = virtualFields().associateBy { sprutLabelKey(it.title) }
        val targets = services.mapNotNull { service ->
            val serviceName = service.scalar("name", "title", "displayName")
            val field = fieldsByTitle[sprutLabelKey(serviceName)] ?: return@mapNotNull null
            val serviceId = service.scalar("sId", "id")
            val characteristics = service.array("characteristics")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
            val expectedType = expectedTypes?.get(field.kind)?.lowercase()
            val candidates = characteristics
                .filterNot { it.isNameCharacteristic() }
                .filter { findBoolean(it, "write") == true }
            val characteristic = expectedType?.let { expected ->
                candidates.firstOrNull { candidate ->
                    candidate.typeIdentifiers().any { actual -> actual.sameTypeAs(expected) }
                }
            } ?: candidates
                .filter { findValueField(it)?.matches(field.kind) == true }
                .singleOrNull()
                ?: return@mapNotNull null
            val characteristicId = characteristic.scalar("cId", "id")
            val valueField = findValueField(characteristic) ?: defaultValueField(field.kind)
            if (serviceId.isBlank() || characteristicId.isBlank()) return@mapNotNull null
            HealthTarget(
                key = field.key,
                serviceId = serviceId,
                characteristicId = characteristicId,
                valueField = valueField,
            )
        }
        if (targets.isEmpty()) return null
        return HealthDeviceBinding(
            accessoryId = accessoryId,
            name = name,
            roomId = roomId,
            targets = targets,
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    private fun validateBinding(
        binding: HealthDeviceBinding,
        fields: List<VirtualFieldSpec>,
        existing: Boolean,
    ) {
        val missing = fields.map(VirtualFieldSpec::key).toSet() - binding.targets.map(HealthTarget::key).toSet()
        check(missing.isEmpty()) {
            val prefix = if (existing) "Существующее" else "Созданное"
            "$prefix устройство здоровья несовместимо: не распознано полей ${missing.size}. " +
                "Удалите аксессуар «${binding.name}» в SprutHub и повторите создание"
        }
        check(binding.targets.distinctBy { "${it.serviceId}:${it.characteristicId}" }.size == binding.targets.size) {
            "Устройство «${binding.name}» содержит неоднозначную привязку характеристик"
        }
        val expectedKinds = fields.associate { it.key to it.kind }
        val incompatible = binding.targets.filter { target ->
            val expected = expectedKinds[target.key] ?: return@filter true
            !target.valueField.matches(expected)
        }
        check(incompatible.isEmpty()) {
            "Устройство «${binding.name}» содержит несовместимые типы полей: " +
                incompatible.joinToString { it.key }
        }
    }

    private suspend fun verifyPublishedValues(
        config: HubConfig,
        binding: HealthDeviceBinding,
        expected: Map<String, JsonPrimitive>,
    ) {
        var lastMismatch = expected.keys.toList()
        repeat(VERIFY_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(VERIFY_RETRY_MS)
            val accessory = listExpandedAccessories(config).firstOrNull {
                it.scalar("id", "aId") == binding.accessoryId
            }
            if (accessory != null) {
                lastMismatch = binding.targets.mapNotNull { target ->
                    val expectedValue = expected[target.key] ?: return@mapNotNull null
                    val actualValue = accessory.valueFor(target) ?: return@mapNotNull target.key
                    target.key.takeUnless { valuesEqual(target.valueField, expectedValue, actualValue) }
                }
                if (lastMismatch.isEmpty()) return
            }
        }
        error(
            "SprutHub принял обновление, но не подтвердил чтением полей: " +
                lastMismatch.joinToString().take(220),
        )
    }

    private fun JsonObject.valueFor(target: HealthTarget): JsonPrimitive? {
        val service = array("services").orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { it.scalar("sId", "id") == target.serviceId }
            ?: return null
        val characteristic = service.array("characteristics").orEmpty()
            .mapNotNull { it as? JsonObject }
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

    private fun valuesEqual(field: String, expected: JsonPrimitive, actual: JsonPrimitive): Boolean {
        return when (field) {
            "boolValue" -> expected.booleanOrNull == actual.booleanOrNull
            "intValue", "longValue", "uintValue", "floatValue", "doubleValue" -> {
                val expectedNumber = expected.content.toDoubleOrNull() ?: return false
                val actualNumber = actual.content.toDoubleOrNull() ?: return false
                abs(expectedNumber - actualNumber) < 0.001
            }
            else -> expected.content == actual.content
        }
    }

    private fun missingFields(binding: HealthDeviceBinding): Set<String> =
        virtualFields().map(VirtualFieldSpec::key).toSet() - binding.targets.map(HealthTarget::key).toSet()

    private fun selectCharacteristicType(types: List<JsonObject>, kind: HealthValueKind): String? {
        val selected = types.firstOrNull { type ->
            val descriptor = listOf(
                type.scalar("type"),
                type.scalar("id"),
                type.scalar("shortId"),
                type.scalar("name"),
                type.scalar("format"),
            ).joinToString(" ").lowercase()
            when (kind) {
                HealthValueKind.INT -> (descriptor.contains("integer") || descriptor.contains("int")) && !descriptor.contains("long")
                HealthValueKind.DOUBLE -> descriptor.contains("float") || descriptor.contains("double")
                HealthValueKind.STRING -> descriptor.contains("string")
                HealthValueKind.BOOL -> descriptor.contains("bool")
            }
        } ?: return null
        return selected.scalar("type", "id", "shortId").takeIf(String::isNotBlank)
    }

    private fun wireValue(field: String, reading: HealthReading): JsonPrimitive? {
        return when (field) {
            "boolValue" -> JsonPrimitive(
                reading.boolValue
                    ?: reading.numberValue?.let { it != 0.0 }
                    ?: reading.stringValue?.let { it == "1" || it.equals("true", true) }
                    ?: return null,
            )
            "intValue", "longValue", "uintValue" -> JsonPrimitive(
                reading.numberValue?.roundToLong()
                    ?: reading.boolValue?.let { if (it) 1L else 0L }
                    ?: reading.stringValue?.toLongOrNull()
                    ?: return null,
            )
            "floatValue", "doubleValue" -> JsonPrimitive(
                reading.numberValue
                    ?: reading.boolValue?.let { if (it) 1.0 else 0.0 }
                    ?: reading.stringValue?.toDoubleOrNull()
                    ?: return null,
            )
            "stringValue", "enumValue" -> JsonPrimitive(
                reading.stringValue
                    ?: reading.numberValue?.toString()
                    ?: reading.boolValue?.toString()
                    ?: return null,
            )
            else -> null
        }
    }

    private fun virtualFields(): List<VirtualFieldSpec> = buildList {
        HealthMetric.entries.forEach { metric -> add(VirtualFieldSpec(metric.name, metric.title, metric.valueKind)) }
        add(VirtualFieldSpec(KEY_PHONE_BATTERY, "Телефон · Заряд", HealthValueKind.INT))
        add(VirtualFieldSpec(KEY_PHONE_CHARGING, "Телефон · Заряжается", HealthValueKind.BOOL))
        add(VirtualFieldSpec(KEY_PHONE_MODEL, "Телефон · Модель", HealthValueKind.STRING))
        add(VirtualFieldSpec(KEY_ANDROID_VERSION, "Телефон · Android", HealthValueKind.STRING))
        add(VirtualFieldSpec(KEY_LAST_SYNC, "Телефон · Синхронизация", HealthValueKind.STRING))
    }

    private fun deviceName(): String = "Здоровье · ${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"

    private suspend fun healthConfig(): HubConfig {
        val current = settings.currentConfig()
        check(current.localUrl.isNotBlank()) {
            "Для здоровья укажите локальный адрес SprutHub в настройках подключения"
        }
        return current.copy(
            mode = ConnectionMode.LOCAL,
            cloudUrl = "",
        )
    }

    private fun request(section: String, operation: String, body: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject { put(section, buildJsonObject { put(operation, body) }) }

    private fun idValue(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)

    private fun findHealthAccessory(accessories: List<JsonObject>, name: String): JsonObject? =
        accessories
            .filter {
                sameSprutLabel(it.scalar("name", "title", "displayName"), name) &&
                    it.boolean("virtual") != false
            }
            .maxByOrNull { it.array("services")?.size ?: 0 }

    private fun findAccessory(element: JsonElement): JsonObject? = findAccessories(element).firstOrNull()

    private fun findAccessories(element: JsonElement): List<JsonObject> {
        val named = findArray(element, "accessories")
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()
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

    private fun hasExpandedCharacteristics(accessory: JsonObject): Boolean = accessory.array("services")
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .any { !it.array("characteristics").isNullOrEmpty() }

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

    private fun findValueField(element: JsonElement): String? = when (element) {
        is JsonObject -> VALUE_FIELDS.firstOrNull(element::containsKey)
            ?: element["value"]?.let(::findValueField)
            ?: element["control"]?.let(::findValueField)
            ?: element.values.firstNotNullOfOrNull(::findValueField)
        is JsonArray -> element.firstNotNullOfOrNull(::findValueField)
        else -> null
    }

    private fun findBoolean(element: JsonElement, key: String): Boolean? = when (element) {
        is JsonObject -> element.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?.value
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
            ?: element.values.firstNotNullOfOrNull { findBoolean(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findBoolean(it, key) }
        else -> null
    }

    private fun defaultValueField(kind: HealthValueKind): String = when (kind) {
        HealthValueKind.INT -> "intValue"
        HealthValueKind.DOUBLE -> "doubleValue"
        HealthValueKind.STRING -> "stringValue"
        HealthValueKind.BOOL -> "boolValue"
    }

    private fun JsonObject.scalar(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, true) }?.value?.let { (it as? JsonPrimitive)?.content }
    }.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean? =
        entries.firstOrNull { it.key.equals(key, true) }?.value?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.array(key: String): JsonArray? =
        entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray

    private fun JsonObject.isNameCharacteristic(): Boolean = listOf(
        scalar("type"),
        scalar("id").takeIf { it.toLongOrNull() == null }.orEmpty(),
        scalar("shortId"),
        scalar("name"),
    ).map { it.lowercase() }.any { it == "name" || it == "c_name" || it.endsWith(".name") }

    private fun JsonObject.typeIdentifiers(): List<String> = buildList {
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key.lowercase() in TYPE_IDENTIFIER_KEYS) {
                        (value as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    collect(value)
                }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        collect(this@typeIdentifiers)
    }.map(String::lowercase).distinct()

    private fun bindingDiagnostic(accessory: JsonObject): String {
        val expected = virtualFields().map { sprutLabelKey(it.title) }.toSet()
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val matched = services.count {
            sprutLabelKey(it.scalar("name", "title", "displayName")) in expected
        }
        val characteristics = services.sumOf { it.array("characteristics")?.size ?: 0 }
        return "Health binding pending: accessoryId=${accessory.scalar("id", "aId")}, " +
            "services=${services.size}, matchedServices=$matched, characteristics=$characteristics"
    }

    private fun String.sameTypeAs(other: String): Boolean = normalizeType() == other.normalizeType()

    private fun String.normalizeType(): String = filter(Char::isLetterOrDigit)

    private fun String.matches(kind: HealthValueKind): Boolean = when (kind) {
        HealthValueKind.INT -> this in setOf("intValue", "longValue", "uintValue")
        HealthValueKind.DOUBLE -> this in setOf("floatValue", "doubleValue")
        HealthValueKind.STRING -> this in setOf("stringValue", "enumValue")
        HealthValueKind.BOOL -> this == "boolValue"
    }

    private data class VirtualFieldSpec(val key: String, val title: String, val kind: HealthValueKind)

    companion object {
        const val KEY_PHONE_BATTERY = "PHONE_BATTERY"
        const val KEY_PHONE_CHARGING = "PHONE_CHARGING"
        const val KEY_PHONE_MODEL = "PHONE_MODEL"
        const val KEY_ANDROID_VERSION = "ANDROID_VERSION"
        const val KEY_LAST_SYNC = "LAST_SYNC"
        private const val OPTION_SERVICE_TYPE = "C_Option"
        private const val EXPAND_PRIMARY = "services,characteristics"
        private const val EXPAND_LEGACY = "services+characteristics"
        private const val BINDING_ATTEMPTS = 8
        private const val BINDING_RETRY_MS = 500L
        private const val VERIFY_ATTEMPTS = 4
        private const val VERIFY_RETRY_MS = 300L
        private const val LOG_TAG = "SprutHubHealth"
        private val VALUE_FIELDS = listOf(
            "boolValue",
            "intValue",
            "longValue",
            "uintValue",
            "floatValue",
            "doubleValue",
            "stringValue",
            "enumValue",
        )
        private val TYPE_IDENTIFIER_KEYS = setOf("type", "shortid", "characteristictype", "typename")
    }
}

internal fun sameSprutLabel(first: String, second: String): Boolean =
    sprutLabelKey(first) == sprutLabelKey(second)

internal fun sprutLabelKey(value: String): String = value
    .lowercase()
    .filter(Char::isLetterOrDigit)
