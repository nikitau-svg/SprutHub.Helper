package io.github.nikitau.spruthubhelper.sprut

import android.os.Build
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthMetric
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.HealthValueKind
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import io.github.nikitau.spruthubhelper.health.HealthReading
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
        // Health information is deliberately LAN-only and never uses the cloud fallback.
        val config = settings.currentConfig().copy(mode = ConnectionMode.LOCAL)
        client.connect(config)
        val deviceName = deviceName()
        val list = client.call(
            config,
            request("accessory", "list", buildJsonObject { put("expand", "services+characteristics") }),
        )
        findArray(list, "accessories")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.scalar("name") == deviceName && it.boolean("virtual") != false }
            ?.let { existing ->
                bindingFromAccessory(existing, roomId, deviceName)?.let { binding ->
                    settings.saveHealthBinding(binding)
                    return binding
                }
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
            put("name", deviceName)
            put("roomId", idValue(roomId))
            put("expand", "services+characteristics")
            put("services", buildJsonArray {
                virtualFields().forEach { field ->
                    add(buildJsonObject {
                        put("type", OPTION_SERVICE_TYPE)
                        put("name", field.title)
                        put("optional", buildJsonArray { add(selectedTypes.getValue(field.kind)) })
                    })
                }
            })
        }
        var accessory = client.call(config, request("accessory", "create", createBody))
        var accessoryObject = findAccessory(accessory)
        val accessoryId = accessoryObject?.scalar("id", "aId").orEmpty()
        if (accessoryObject?.array("services").isNullOrEmpty() && accessoryId.isNotBlank()) {
            accessory = client.call(
                config,
                request("accessory", "get", buildJsonObject {
                    put("id", idValue(accessoryId))
                    put("expand", "services+characteristics")
                }),
            )
            accessoryObject = findAccessory(accessory)
        }
        val binding = accessoryObject?.let { bindingFromAccessory(it, roomId, deviceName) }
            ?: error("SprutHub создал аксессуар, но не вернул его характеристики")
        check(binding.targets.size >= virtualFields().size - 1) {
            "SprutHub создал не все характеристики (${binding.targets.size}/${virtualFields().size})"
        }
        settings.saveHealthBinding(binding)
        return binding
    }

    suspend fun publish(binding: HealthDeviceBinding, readings: Map<String, HealthReading>) {
        // Health information is deliberately LAN-only and never uses the cloud fallback.
        val config = settings.currentConfig().copy(mode = ConnectionMode.LOCAL)
        client.connect(config)
        binding.targets.forEach { target ->
            val reading = readings[target.key] ?: return@forEach
            val value = when {
                reading.boolValue != null -> JsonPrimitive(reading.boolValue)
                reading.stringValue != null -> JsonPrimitive(reading.stringValue)
                target.valueField in setOf("intValue", "longValue") -> JsonPrimitive(reading.numberValue?.toLong() ?: return@forEach)
                else -> JsonPrimitive(reading.numberValue ?: return@forEach)
            }
            val body = buildJsonObject {
                put("aId", idValue(binding.accessoryId))
                put("sId", idValue(target.serviceId))
                put("cId", idValue(target.characteristicId))
                put("control", buildJsonObject {
                    put("value", buildJsonObject { put(target.valueField, value) })
                })
            }
            client.call(config, request("characteristic", "update", body))
        }
    }

    private fun bindingFromAccessory(
        accessory: JsonObject,
        roomId: String,
        name: String,
    ): HealthDeviceBinding? {
        val accessoryId = accessory.scalar("id", "aId")
        if (accessoryId.isBlank()) return null
        val services = accessory.array("services").orEmpty().mapNotNull { it as? JsonObject }
        val fieldsByTitle = virtualFields().associateBy(VirtualFieldSpec::title)
        val targets = services.mapNotNull { service ->
            val field = fieldsByTitle[service.scalar("name")] ?: return@mapNotNull null
            val serviceId = service.scalar("sId", "id")
            val characteristic = service.array("characteristics")
                .orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { candidate ->
                    val descriptor = candidate.toString().lowercase()
                    !descriptor.contains("\"name\"") && when (field.kind) {
                        HealthValueKind.INT -> descriptor.contains("integer") || descriptor.contains("intvalue")
                        HealthValueKind.DOUBLE -> descriptor.contains("float") || descriptor.contains("double")
                        HealthValueKind.STRING -> descriptor.contains("string")
                        HealthValueKind.BOOL -> descriptor.contains("bool")
                    }
                }
                ?: service.array("characteristics").orEmpty().mapNotNull { it as? JsonObject }.lastOrNull()
                ?: return@mapNotNull null
            val characteristicId = characteristic.scalar("cId", "id")
            if (serviceId.isBlank() || characteristicId.isBlank()) return@mapNotNull null
            HealthTarget(
                key = field.key,
                serviceId = serviceId,
                characteristicId = characteristicId,
                valueField = when (field.kind) {
                    HealthValueKind.INT -> "intValue"
                    HealthValueKind.DOUBLE -> "doubleValue"
                    HealthValueKind.STRING -> "stringValue"
                    HealthValueKind.BOOL -> "boolValue"
                },
            )
        }
        if (targets.isEmpty()) return null
        return HealthDeviceBinding(
            accessoryId = accessoryId,
            name = name,
            roomId = roomId,
            targets = targets,
        )
    }

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

    private fun virtualFields(): List<VirtualFieldSpec> = buildList {
        HealthMetric.entries.forEach { metric -> add(VirtualFieldSpec(metric.name, metric.title, metric.valueKind)) }
        add(VirtualFieldSpec(KEY_PHONE_BATTERY, "Телефон · Заряд", HealthValueKind.INT))
        add(VirtualFieldSpec(KEY_PHONE_CHARGING, "Телефон · Заряжается", HealthValueKind.BOOL))
        add(VirtualFieldSpec(KEY_PHONE_MODEL, "Телефон · Модель", HealthValueKind.STRING))
        add(VirtualFieldSpec(KEY_ANDROID_VERSION, "Телефон · Android", HealthValueKind.STRING))
        add(VirtualFieldSpec(KEY_LAST_SYNC, "Телефон · Синхронизация", HealthValueKind.STRING))
    }

    private fun deviceName(): String = "Здоровье · ${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}"

    private fun request(section: String, operation: String, body: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject { put(section, buildJsonObject { put(operation, body) }) }

    private fun idValue(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)

    private fun findAccessory(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> if (element.scalar("id", "aId").isNotBlank() && element.array("services") != null) element
        else element.values.firstNotNullOfOrNull(::findAccessory)
        is JsonArray -> element.firstNotNullOfOrNull(::findAccessory)
        else -> null
    }

    private fun findArray(element: JsonElement, key: String): JsonArray? = when (element) {
        is JsonObject -> element.entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray
            ?: element.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }

    private fun JsonObject.scalar(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        entries.firstOrNull { it.key.equals(key, true) }?.value?.let { (it as? JsonPrimitive)?.content }
    }.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean? =
        entries.firstOrNull { it.key.equals(key, true) }?.value?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.array(key: String): JsonArray? =
        entries.firstOrNull { it.key.equals(key, true) }?.value as? JsonArray

    private data class VirtualFieldSpec(val key: String, val title: String, val kind: HealthValueKind)

    companion object {
        const val KEY_PHONE_BATTERY = "PHONE_BATTERY"
        const val KEY_PHONE_CHARGING = "PHONE_CHARGING"
        const val KEY_PHONE_MODEL = "PHONE_MODEL"
        const val KEY_ANDROID_VERSION = "ANDROID_VERSION"
        const val KEY_LAST_SYNC = "LAST_SYNC"
        private const val OPTION_SERVICE_TYPE = "C_Option"
    }
}
