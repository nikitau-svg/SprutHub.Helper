package io.github.nikitau.spruthubhelper.sprut

import android.os.Build
import android.util.Log
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HealthValueKind
import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.PhoneSensor
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

enum class VirtualDeviceProfile(
    val title: String,
    val devicePrefix: String,
    val logTag: String,
) {
    HEALTH("здоровья", "Здоровье", "SprutHubHealth"),
    PHONE("телефона", "Телефон", "SprutHubPhone"),
}

data class VirtualFieldSpec(
    val key: String,
    val title: String,
    val kind: HealthValueKind,
    val required: Boolean = true,
)

class VirtualHealthDeviceManager(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
    private val profile: VirtualDeviceProfile = VirtualDeviceProfile.HEALTH,
) {
    suspend fun createOrRecover(
        roomId: String,
        fields: List<VirtualFieldSpec> = defaultVirtualFields(),
    ): HealthDeviceBinding {
        require(fields.isNotEmpty()) { "Выберите хотя бы один показатель" }
        val config = deviceConfig()
        client.connect(config)
        val name = deviceName()
        Log.i(profile.logTag, "Virtual ${profile.name.lowercase()} device create/recover started")

        findHealthAccessory(listExpandedAccessories(config), name)?.let { existing ->
            val binding = awaitCompleteBinding(
                config = config,
                accessoryId = existing.scalar("id", "aId"),
                name = name,
                fallbackRoomId = roomId,
                expectedTypes = null,
                existing = true,
                fields = fields,
            )
            validateBinding(binding, fields, existing = true)
            Log.i(profile.logTag, "Existing virtual device recovered with ${binding.targets.size} targets")
            return binding
        }

        val serviceTypes = client.call(config, request("service", "types"))
        val types = findArray(serviceTypes, "types").orEmpty().mapNotNull { it as? JsonObject }
        val optionService = types.firstOrNull { it.scalar("type", "id", "shortId") == OPTION_SERVICE_TYPE }
            ?: error("В этой версии SprutHub нет универсального сервиса C_Option")
        val characteristicTypes = optionService.array("optional").orEmpty().mapNotNull { it as? JsonObject }
        val selectedTypes = fields.map(VirtualFieldSpec::kind).distinct().associateWith { kind ->
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
            fields = fields,
        )
        validateBinding(binding, fields, existing = false)
        Log.i(profile.logTag, "Virtual device created with ${binding.targets.size} targets")
        return binding
    }

    suspend fun recoverExisting(
        fields: List<VirtualFieldSpec> = defaultVirtualFields(),
    ): HealthDeviceBinding? {
        require(fields.isNotEmpty()) { "Выберите хотя бы один показатель" }
        val config = deviceConfig()
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
            fields = fields,
        )
        validateBinding(binding, fields, existing = true)
        Log.i(profile.logTag, "Virtual device binding repaired automatically")
        return binding
    }

    suspend fun publish(
        binding: HealthDeviceBinding,
        readings: Map<String, HealthReading>,
        fields: List<VirtualFieldSpec> = defaultVirtualFields(),
    ): HealthDeviceBinding {
        require(fields.isNotEmpty()) { "Нет настроенных полей для публикации" }
        val config = deviceConfig()
        client.connect(config)
        val liveBinding = awaitCompleteBinding(
            config = config,
            accessoryId = binding.accessoryId,
            name = binding.name,
            fallbackRoomId = binding.roomId,
            expectedTypes = null,
            existing = true,
            createdAtEpochMs = binding.createdAtEpochMs,
            fields = fields,
        )
        validateBinding(liveBinding, fields, existing = true)

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
                Log.e(profile.logTag, "Virtual target ${target.key} failed", error)
            }
        }
        check(published > 0) { "Нет данных, подходящих для публикации в SprutHub" }
        check(failures.isEmpty()) {
            "Обновлено $published полей, не обновлено ${failures.size}: ${failures.joinToString().take(240)}"
        }
        verifyPublishedValues(config, liveBinding, expectedValues)
        Log.i(profile.logTag, "Virtual device published and verified $published fields")
        return liveBinding
    }

    suspend fun recreate(
        binding: HealthDeviceBinding,
        roomId: String,
        fields: List<VirtualFieldSpec>,
    ): HealthDeviceBinding {
        require(fields.isNotEmpty()) { "Выберите хотя бы один показатель" }
        val config = deviceConfig()
        client.connect(config)
        val expectedName = deviceName()
        val accessory = listExpandedAccessories(config).firstOrNull { candidate ->
            candidate.scalar("id", "aId") == binding.accessoryId
        } ?: error("Устройство «${binding.name}» больше не найдено в SprutHub")
        check(sameSprutLabel(accessory.scalar("name", "title", "displayName"), expectedName)) {
            "Защитная проверка остановила удаление: имя устройства изменилось"
        }
        check(accessory.boolean("virtual") != false) {
            "Защитная проверка остановила удаление: устройство больше не виртуальное"
        }

        client.call(
            config,
            request(
                "accessory",
                "delete",
                buildJsonObject { put("id", idValue(binding.accessoryId)) },
            ),
        )
        repeat(DELETE_VERIFY_ATTEMPTS) { attempt ->
            val stillExists = listExpandedAccessories(config).any { candidate ->
                candidate.scalar("id", "aId") == binding.accessoryId
            }
            if (!stillExists) return createOrRecover(roomId, fields)
            if (attempt < DELETE_VERIFY_ATTEMPTS - 1) delay(DELETE_VERIFY_RETRY_MS)
        }
        error("SprutHub принял удаление, но устройство всё ещё отображается")
    }

    private suspend fun awaitCompleteBinding(
        config: HubConfig,
        accessoryId: String,
        name: String,
        fallbackRoomId: String,
        expectedTypes: Map<HealthValueKind, String>?,
        existing: Boolean,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        fields: List<VirtualFieldSpec>,
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
                            fields = fields,
                        )
                        if (candidate != null) {
                            val missing = missingFields(candidate, fields)
                            if (missing.isEmpty()) return candidate
                            lastProblem = "не распознано полей ${missing.size}"
                            Log.w(
                                profile.logTag,
                                "Virtual binding incomplete: targets=${candidate.targets.size}, " +
                                    "missing=${missing.sorted().joinToString()}",
                            )
                        } else {
                            lastProblem = "структура характеристик ещё не распознана"
                            Log.w(profile.logTag, bindingDiagnostic(accessory, fields))
                        }
                    }
                }
                .onFailure { error -> lastProblem = error.message ?: "ошибка accessory.list" }
            if (attempt < BINDING_ATTEMPTS - 1) delay(BINDING_RETRY_MS)
        }
        val prefix = if (existing) "Существующее" else "Созданное"
        error("$prefix устройство ${profile.title} не готово: $lastProblem")
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
        fields: List<VirtualFieldSpec>,
    ): HealthDeviceBinding? {
        val accessoryId = accessory.scalar("id", "aId")
        if (accessoryId.isBlank()) return null
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val fieldsByTitle = fields.associateBy { sprutLabelKey(it.title) }
        val targets = services.mapNotNull { service ->
            val serviceName = service.scalar("name", "title", "displayName")
            val field = fieldsByTitle[sprutLabelKey(serviceName)] ?: return@mapNotNull null
            val serviceId = service.scalar("sId", "id")
            val characteristics = service.array("characteristics")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
            val expectedType = expectedTypes?.get(field.kind)?.lowercase()
            // `write` describes whether a person may control a characteristic
            // from the SprutHub UI. Read-only sensor characteristics are still
            // updated by their virtual accessory provider through
            // characteristic.update, which is exactly our role here.
            val candidates = characteristics.filterNot { it.isNameCharacteristic() }
            val characteristic = expectedType?.let { expected ->
                candidates.firstOrNull { candidate ->
                    candidate.typeIdentifiers().any { actual -> actual.sameTypeAs(expected) }
                }
            } ?: candidates
                .filter { findValueField(it)?.matches(field.kind) == true }
                .singleOrNull()
                ?: candidates
                    .filter { candidate -> candidate.typeIdentifiers().matches(field.kind) }
                    .singleOrNull()
                // SprutHub omits the value object for an empty GenericString.
                // A service created by this app has exactly one data
                // characteristic besides C_Name, so this remains unambiguous.
                ?: candidates.singleOrNull()
                ?: run {
                    Log.w(profile.logTag, healthFieldDiagnostic(field, serviceId, characteristics))
                    return@mapNotNull null
                }
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
        val missing = fields.filter(VirtualFieldSpec::required).map(VirtualFieldSpec::key).toSet() -
            binding.targets.map(HealthTarget::key).toSet()
        check(missing.isEmpty()) {
            val prefix = if (existing) "Существующее" else "Созданное"
            "$prefix устройство ${profile.title} несовместимо: не распознано полей ${missing.size}. " +
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

    private fun missingFields(binding: HealthDeviceBinding, fields: List<VirtualFieldSpec>): Set<String> =
        fields.filter(VirtualFieldSpec::required).map(VirtualFieldSpec::key).toSet() -
            binding.targets.map(HealthTarget::key).toSet()

    private fun selectCharacteristicType(types: List<JsonObject>, kind: HealthValueKind): String? {
        val selected = types.firstOrNull { type ->
            if (type.isNameCharacteristic()) return@firstOrNull false
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

    private fun defaultVirtualFields(): List<VirtualFieldSpec> = when (profile) {
        VirtualDeviceProfile.HEALTH -> healthVirtualFields(HealthMetric.entries.toSet())
        VirtualDeviceProfile.PHONE -> phoneVirtualFields(PhoneSensor.entries.toSet())
    }

    private fun deviceName(): String =
        "${profile.devicePrefix} · ${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"

    private suspend fun deviceConfig(): HubConfig {
        val current = settings.currentConfig()
        if (profile == VirtualDeviceProfile.PHONE) {
            return current
        }
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

    private fun JsonObject.isNameCharacteristic(): Boolean = (
        listOf(
            scalar("type"),
            scalar("id").takeIf { it.toLongOrNull() == null }.orEmpty(),
            scalar("shortId"),
            scalar("name"),
        ) + typeIdentifiers()
        ).any(::isSprutNameTypeIdentifier)

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

    private fun bindingDiagnostic(accessory: JsonObject, fields: List<VirtualFieldSpec>): String {
        val expected = fields.map { sprutLabelKey(it.title) }.toSet()
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val matched = services.count {
            sprutLabelKey(it.scalar("name", "title", "displayName")) in expected
        }
        val characteristics = services.sumOf { it.array("characteristics")?.size ?: 0 }
        return "Virtual binding pending: accessoryId=${accessory.scalar("id", "aId")}, " +
            "services=${services.size}, matchedServices=$matched, characteristics=$characteristics"
    }

    private fun healthFieldDiagnostic(
        field: VirtualFieldSpec,
        serviceId: String,
        characteristics: List<JsonObject>,
    ): String {
        val metadata = characteristics.joinToString(separator = ";", limit = 6) { characteristic ->
            val id = characteristic.scalar("cId", "id")
            val types = characteristic.typeIdentifiers().joinToString("|").take(120)
            val valueField = findValueField(characteristic).orEmpty()
            val write = findBoolean(characteristic, "write")
            "id=$id,name=${characteristic.isNameCharacteristic()},write=$write,valueField=$valueField,types=$types"
        }
        return "Virtual field unresolved: key=${field.key}, serviceId=$serviceId, " +
            "characteristics=${characteristics.size}, metadata=$metadata"
    }

    private fun String.sameTypeAs(other: String): Boolean = normalizeType() == other.normalizeType()

    private fun String.normalizeType(): String = filter(Char::isLetterOrDigit)

    private fun String.matches(kind: HealthValueKind): Boolean = when (kind) {
        HealthValueKind.INT -> this in setOf("intValue", "longValue", "uintValue")
        HealthValueKind.DOUBLE -> this in setOf("floatValue", "doubleValue")
        HealthValueKind.STRING -> this in setOf("stringValue", "enumValue")
        HealthValueKind.BOOL -> this == "boolValue"
    }

    private fun List<String>.matches(kind: HealthValueKind): Boolean = any { descriptor ->
        healthTypeDescriptorMatches(descriptor, kind)
    }

    companion object {
        private const val OPTION_SERVICE_TYPE = "C_Option"
        private const val EXPAND_PRIMARY = "services,characteristics"
        private const val EXPAND_LEGACY = "services+characteristics"
        private const val BINDING_ATTEMPTS = 8
        private const val BINDING_RETRY_MS = 500L
        private const val VERIFY_ATTEMPTS = 4
        private const val VERIFY_RETRY_MS = 300L
        private const val DELETE_VERIFY_ATTEMPTS = 10
        private const val DELETE_VERIFY_RETRY_MS = 300L
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
        private val TYPE_IDENTIFIER_KEYS = setOf(
            "type",
            "id",
            "shortid",
            "characteristictype",
            "typename",
            "format",
        )
    }
}

fun healthVirtualFields(metrics: Set<HealthMetric>): List<VirtualFieldSpec> =
    HealthMetric.entries
        .filter(metrics::contains)
        .map { metric -> VirtualFieldSpec(metric.name, metric.title, metric.valueKind) }

fun phoneVirtualFields(sensors: Set<PhoneSensor>): List<VirtualFieldSpec> =
    PhoneSensor.entries
        .filter(sensors::contains)
        .map { sensor -> VirtualFieldSpec(sensor.name, sensor.title, sensor.valueKind) }

internal fun healthTypeDescriptorMatches(descriptor: String, kind: HealthValueKind): Boolean {
    val normalized = descriptor.lowercase().filter(Char::isLetterOrDigit)
    return when (kind) {
        HealthValueKind.INT -> (normalized.contains("integer") || normalized.contains("int")) &&
            !normalized.contains("long")
        HealthValueKind.DOUBLE -> normalized.contains("float") || normalized.contains("double")
        HealthValueKind.STRING -> normalized.contains("string") || normalized.contains("text")
        HealthValueKind.BOOL -> normalized.contains("bool")
    }
}

internal fun isSprutNameTypeIdentifier(value: String): Boolean {
    val normalized = value.lowercase().filter(Char::isLetterOrDigit)
    return normalized == "name" || normalized == "cname" || normalized == "characteristicname"
}

internal fun sameSprutLabel(first: String, second: String): Boolean =
    sprutLabelKey(first) == sprutLabelKey(second)

internal fun sprutLabelKey(value: String): String = value
    .lowercase()
    .filter(Char::isLetterOrDigit)
