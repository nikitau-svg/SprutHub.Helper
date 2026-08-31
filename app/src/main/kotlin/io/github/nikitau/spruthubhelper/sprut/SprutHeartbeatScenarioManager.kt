package io.github.nikitau.spruthubhelper.sprut

import android.util.Log
import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.PHONE_HEARTBEAT_SCENARIO_NAME
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import io.github.nikitau.spruthubhelper.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class HeartbeatProtectionStatus {
    NOT_CONFIGURED,
    READY,
    REPAIRED,
    PAUSED,
    NEEDS_REPAIR,
    CONFLICT,
    ERROR,
}

data class HeartbeatProtectionReport(
    val status: HeartbeatProtectionStatus = HeartbeatProtectionStatus.NOT_CONFIGURED,
    val scenarioIndex: String? = null,
    val appOwnedScenarioCount: Int = 0,
    val foreignSameNameCount: Int = 0,
    val notificationServiceCount: Int? = null,
    val message: String = "Защита ещё не проверялась",
) {
    val ready: Boolean
        get() = status == HeartbeatProtectionStatus.READY || status == HeartbeatProtectionStatus.REPAIRED
}

/**
 * Maintains the SprutHub-side dead-man timer for phone synchronization.
 *
 * The scenario is deliberately identified by both a stable name and an owner
 * marker in its description. Name-only matches are never changed or deleted.
 */
class SprutHeartbeatScenarioManager(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
) {
    private val mutex = Mutex()

    suspend fun inspect(binding: HealthDeviceBinding): HeartbeatProtectionReport = mutex.withLock {
        reconcile(binding, repair = false)
    }

    suspend fun ensure(binding: HealthDeviceBinding): HeartbeatProtectionReport = mutex.withLock {
        reconcile(binding, repair = true)
    }

    suspend fun pause(binding: HealthDeviceBinding): HeartbeatProtectionReport = mutex.withLock {
        val target = binding.targets.firstOrNull { it.key == PhoneSensor.SYNC_HEARTBEAT.name }
            ?: return@withLock HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.PAUSED,
                message = "Фоновая синхронизация выключена; служебный сценарий не используется",
            )
        validateNumericIds(binding, target)
        val config = settings.currentConfig()
        client.connect(config)
        val sameName = loadSameNameScenarios(config)
        val owned = sameName.filter(ScenarioRecord::isOwned)
        val foreign = sameName.filterNot(ScenarioRecord::isOwned)
        if (owned.isEmpty()) {
            settings.savePhoneHeartbeatScenarioIndex(null)
            return@withLock HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.PAUSED,
                foreignSameNameCount = foreign.size,
                notificationServiceCount = loadNotificationServiceCount(config),
                message = "Фоновая синхронизация выключена; активного сценария Helper нет",
            )
        }
        val storedIndex = settings.phoneHeartbeatScenarioIndex.first()
        val preferred = owned.firstOrNull { it.index == storedIndex }
            ?: owned.minWithOrNull(compareBy<ScenarioRecord> { it.index.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.index })
            ?: error("Не удалось выбрать служебный сценарий")
        if (preferred.active != false || !heartbeatScenarioHasCurrentTarget(preferred, binding, target)) {
            client.call(
                config,
                request(
                    "scenario",
                    "update",
                    buildJsonObject {
                        put("index", preferred.index)
                        scenarioBody(binding, target, active = false).forEach(::put)
                    },
                ),
            )
        }
        owned.filterNot { it.index == preferred.index }.forEach { duplicate ->
            client.call(
                config,
                request(
                    "scenario",
                    "delete",
                    buildJsonObject { put("index", duplicate.index) },
                ),
            )
        }
        settings.savePhoneHeartbeatScenarioIndex(preferred.index)
        val verified = awaitSameNameScenarios(config) { scenarios ->
            val appOwned = scenarios.filter(ScenarioRecord::isOwned)
            appOwned.size == 1 && appOwned.single().active == false &&
                heartbeatScenarioHasCurrentTarget(appOwned.single(), binding, target)
        }
        val verifiedOwned = verified.filter(ScenarioRecord::isOwned)
        val verifiedPreferred = verifiedOwned.firstOrNull { it.index == preferred.index }
        if (
            verifiedOwned.size != 1 ||
            verifiedPreferred == null ||
            verifiedPreferred.active != false ||
            !heartbeatScenarioHasCurrentTarget(verifiedPreferred, binding, target)
        ) {
            return@withLock HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.ERROR,
                scenarioIndex = verifiedPreferred?.index,
                appOwnedScenarioCount = verifiedOwned.size,
                foreignSameNameCount = foreign.size,
                notificationServiceCount = loadNotificationServiceCount(config),
                message = "SprutHub не подтвердил остановку служебной тревоги",
            )
        }
        HeartbeatProtectionReport(
            status = HeartbeatProtectionStatus.PAUSED,
            scenarioIndex = preferred.index,
            appOwnedScenarioCount = 1,
            foreignSameNameCount = foreign.size,
            notificationServiceCount = loadNotificationServiceCount(config),
            message = "Фоновая синхронизация выключена; тревога SprutHub приостановлена",
        )
    }

    private suspend fun reconcile(
        binding: HealthDeviceBinding,
        repair: Boolean,
    ): HeartbeatProtectionReport {
        val target = binding.targets.firstOrNull { it.key == PhoneSensor.SYNC_HEARTBEAT.name }
            ?: return HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.NOT_CONFIGURED,
                message = "В устройстве телефона нет служебного поля «Пульс синхронизации»",
            )
        validateNumericIds(binding, target)

        val config = settings.currentConfig()
        client.connect(config)
        var sameName = loadSameNameScenarios(config)
        var owned = sameName.filter(ScenarioRecord::isOwned)
        var foreign = sameName.filterNot(ScenarioRecord::isOwned)
        val storedIndex = settings.phoneHeartbeatScenarioIndex.first()
        var preferred = owned.firstOrNull { it.index == storedIndex }
            ?: owned.minWithOrNull(compareBy<ScenarioRecord> { it.index.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.index })
        var changed = false

        if (!repair) {
            val notificationCount = loadNotificationServiceCount(config)
            return inspectionReport(preferred, owned, foreign, target, binding, notificationCount)
        }

        if (preferred == null && foreign.isNotEmpty()) {
            settings.savePhoneHeartbeatScenarioIndex(null)
            return HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.CONFLICT,
                foreignSameNameCount = foreign.size,
                notificationServiceCount = loadNotificationServiceCount(config),
                message = "В SprutHub уже есть чужой сценарий с таким именем. Helper не стал его менять — переименуйте его и повторите проверку",
            )
        }

        if (preferred == null) {
            val response = client.call(config, request("scenario", "create", scenarioBody(binding, target)))
            val createdIndex = findScalar(response, "index", "id")
            if (createdIndex.isNotBlank()) settings.savePhoneHeartbeatScenarioIndex(createdIndex)
            changed = true
            sameName = awaitSameNameScenarios(config)
            owned = sameName.filter(ScenarioRecord::isOwned)
            foreign = sameName.filterNot(ScenarioRecord::isOwned)
            preferred = owned.firstOrNull { it.index == createdIndex }
                ?: owned.minWithOrNull(compareBy<ScenarioRecord> { it.index.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.index })
        }

        preferred ?: return HeartbeatProtectionReport(
            status = HeartbeatProtectionStatus.ERROR,
            foreignSameNameCount = foreign.size,
            notificationServiceCount = loadNotificationServiceCount(config),
            message = "SprutHub принял создание защиты, но не вернул созданный сценарий",
        )

        if (!heartbeatScenarioIsCurrent(preferred, binding, target)) {
            client.call(
                config,
                request(
                    "scenario",
                    "update",
                    buildJsonObject {
                        put("index", preferred.index)
                        scenarioBody(binding, target).forEach(::put)
                    },
                ),
            )
            changed = true
        }

        // Only descriptions carrying our exact marker authorize deletion.
        owned.filterNot { it.index == preferred.index }.forEach { duplicate ->
            client.call(
                config,
                request(
                    "scenario",
                    "delete",
                    buildJsonObject { put("index", duplicate.index) },
                ),
            )
            changed = true
            Log.w(LOG_TAG, "Removed app-owned duplicate heartbeat scenario index=${duplicate.index}")
        }

        settings.savePhoneHeartbeatScenarioIndex(preferred.index)
        val verified = awaitSameNameScenarios(config) { scenarios ->
            val appOwned = scenarios.filter(ScenarioRecord::isOwned)
            appOwned.size == 1 && heartbeatScenarioIsCurrent(appOwned.single(), binding, target)
        }
        val verifiedOwned = verified.filter(ScenarioRecord::isOwned)
        val verifiedForeign = verified.filterNot(ScenarioRecord::isOwned)
        val verifiedPreferred = verifiedOwned.firstOrNull { it.index == preferred.index }
        val notificationCount = loadNotificationServiceCount(config)
        if (
            verifiedOwned.size != 1 ||
            verifiedPreferred == null ||
            !heartbeatScenarioIsCurrent(verifiedPreferred, binding, target)
        ) {
            return HeartbeatProtectionReport(
                status = HeartbeatProtectionStatus.ERROR,
                scenarioIndex = verifiedPreferred?.index,
                appOwnedScenarioCount = verifiedOwned.size,
                foreignSameNameCount = verifiedForeign.size,
                notificationServiceCount = notificationCount,
                message = "Защита была изменена, но контрольная проверка SprutHub не подтвердила целостность",
            )
        }

        return HeartbeatProtectionReport(
            status = if (changed) HeartbeatProtectionStatus.REPAIRED else HeartbeatProtectionStatus.READY,
            scenarioIndex = verifiedPreferred.index,
            appOwnedScenarioCount = 1,
            foreignSameNameCount = verifiedForeign.size,
            notificationServiceCount = notificationCount,
            message = buildString {
                append(if (changed) "Защита синхронизации восстановлена" else "Защита синхронизации работает")
                append(": SprutHub предупредит после 45 минут без обновлений")
                if (verifiedForeign.isNotEmpty()) append(". Одноимённые чужие сценарии не изменялись")
                if (notificationCount == 0) append(". Настройте сервис уведомлений в SprutHub")
            },
        )
    }

    private fun inspectionReport(
        preferred: ScenarioRecord?,
        owned: List<ScenarioRecord>,
        foreign: List<ScenarioRecord>,
        target: HealthTarget,
        binding: HealthDeviceBinding,
        notificationCount: Int?,
    ): HeartbeatProtectionReport {
        val current = preferred?.let { heartbeatScenarioIsCurrent(it, binding, target) } == true
        val status = when {
            owned.isEmpty() && foreign.isNotEmpty() -> HeartbeatProtectionStatus.CONFLICT
            owned.isEmpty() -> HeartbeatProtectionStatus.NEEDS_REPAIR
            owned.size > 1 || !current -> HeartbeatProtectionStatus.NEEDS_REPAIR
            else -> HeartbeatProtectionStatus.READY
        }
        val message = when (status) {
            HeartbeatProtectionStatus.READY -> "Защита синхронизации работает"
            HeartbeatProtectionStatus.CONFLICT -> "Найден чужой одноимённый сценарий; автоматическое изменение заблокировано"
            else -> "Служебный сценарий отсутствует, выключен, устарел или продублирован"
        }
        return HeartbeatProtectionReport(
            status = status,
            scenarioIndex = preferred?.index,
            appOwnedScenarioCount = owned.size,
            foreignSameNameCount = foreign.size,
            notificationServiceCount = notificationCount,
            message = if (notificationCount == 0 && status == HeartbeatProtectionStatus.READY) {
                "$message. В SprutHub не найден настроенный сервис уведомлений"
            } else {
                message
            },
        )
    }

    private suspend fun loadSameNameScenarios(config: io.github.nikitau.spruthubhelper.data.HubConfig): List<ScenarioRecord> {
        val list = client.call(config, request("scenario", "list"))
        return findScenarioArray(list)
            .mapNotNull(::scenarioRecord)
            .filter { sameSprutLabel(it.name, PHONE_HEARTBEAT_SCENARIO_NAME) }
            .map { summary ->
                runCatching {
                    val detail = client.call(
                        config,
                        request("scenario", "get", buildJsonObject { put("index", summary.index) }),
                    )
                    findScenario(detail, summary.index) ?: summary
                }.getOrDefault(summary)
            }
            .distinctBy(ScenarioRecord::index)
    }

    private suspend fun awaitSameNameScenarios(
        config: io.github.nikitau.spruthubhelper.data.HubConfig,
        ready: (List<ScenarioRecord>) -> Boolean = { scenarios ->
            val owned = scenarios.filter(ScenarioRecord::isOwned)
            owned.isNotEmpty() && owned.size <= 1
        },
    ): List<ScenarioRecord> {
        var latest = emptyList<ScenarioRecord>()
        repeat(VERIFY_ATTEMPTS) { attempt ->
            latest = loadSameNameScenarios(config)
            if (ready(latest)) return latest
            if (attempt < VERIFY_ATTEMPTS - 1) delay(VERIFY_DELAY_MS)
        }
        return latest
    }

    private suspend fun loadNotificationServiceCount(
        config: io.github.nikitau.spruthubhelper.data.HubConfig,
    ): Int? = runCatching {
        val response = client.call(config, request("notification", "list"))
        listOf("notifications", "notificationServices", "services")
            .firstNotNullOfOrNull { key -> findArray(response, key) }
            ?.mapNotNull { it as? JsonObject }
            ?.count { it.boolean("active", "enabled") != false }
    }.getOrNull()

    private fun findScenarioArray(element: JsonElement): JsonArray =
        findArray(element, "scenarios") ?: JsonArray(emptyList())

    private fun findScenario(element: JsonElement, expectedIndex: String): ScenarioRecord? = when (element) {
        is JsonObject -> scenarioRecord(element)?.takeIf { it.index == expectedIndex }
            ?: element.values.firstNotNullOfOrNull { findScenario(it, expectedIndex) }
        is JsonArray -> element.firstNotNullOfOrNull { findScenario(it, expectedIndex) }
        else -> null
    }

    private fun scenarioRecord(value: JsonElement): ScenarioRecord? {
        val objectValue = value as? JsonObject ?: return null
        val index = objectValue.scalar("index", "id")
        val name = objectValue.scalar("name", "title")
        if (index.isBlank() || name.isBlank()) return null
        return ScenarioRecord(
            index = index,
            name = name,
            description = objectValue.scalar("desc", "description"),
            active = objectValue.boolean("active", "enabled"),
            onStart = objectValue.boolean("onStart"),
            type = objectValue.scalar("type"),
            data = objectValue.scalar("data"),
        )
    }

    private fun request(section: String, operation: String, body: JsonObject = buildJsonObject {}): JsonObject =
        buildJsonObject { put(section, buildJsonObject { put(operation, body) }) }

    private fun validateNumericIds(binding: HealthDeviceBinding, target: HealthTarget) {
        require(binding.accessoryId.toLongOrNull() != null) { "SprutHub вернул некорректный ID устройства телефона" }
        require(target.serviceId.toLongOrNull() != null) { "SprutHub вернул некорректный ID сервиса пульса" }
        require(target.characteristicId.toLongOrNull() != null) { "SprutHub вернул некорректный ID характеристики пульса" }
    }

    private fun findScalar(element: JsonElement, vararg keys: String): String = when (element) {
        is JsonObject -> element.entries.firstNotNullOfOrNull { (key, value) ->
            if (keys.any { it.equals(key, ignoreCase = true) }) {
                (value as? JsonPrimitive)?.contentOrNull
            } else {
                null
            }
        } ?: element.values.firstNotNullOfOrNull { findScalar(it, *keys).takeIf(String::isNotBlank) }.orEmpty()
        is JsonArray -> element.firstNotNullOfOrNull { findScalar(it, *keys).takeIf(String::isNotBlank) }.orEmpty()
        else -> ""
    }

    private fun findArray(element: JsonElement, key: String): JsonArray? = when (element) {
        is JsonObject -> element.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value as? JsonArray
            ?: element.values.firstNotNullOfOrNull { findArray(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findArray(it, key) }
        else -> null
    }

    private fun JsonObject.scalar(vararg keys: String): String = keys.firstNotNullOfOrNull { expected ->
        entries.firstOrNull { it.key.equals(expected, ignoreCase = true) }
            ?.value
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.orEmpty()

    private fun JsonObject.boolean(vararg keys: String): Boolean? = keys.firstNotNullOfOrNull { expected ->
        entries.firstOrNull { it.key.equals(expected, ignoreCase = true) }
            ?.value
            ?.let { (it as? JsonPrimitive)?.booleanOrNull }
    }

    private companion object {
        const val LOG_TAG = "SprutHubHeartbeat"
        const val VERIFY_ATTEMPTS = 4
        const val VERIFY_DELAY_MS = 350L
    }
}

internal const val PHONE_HEARTBEAT_OWNER_MARKER = "[spruthub-helper:phone-heartbeat:v1]"
internal const val PHONE_HEARTBEAT_TIMEOUT_MS = 45 * 60 * 1_000L

internal data class ScenarioRecord(
    val index: String,
    val name: String,
    val description: String,
    val active: Boolean?,
    val onStart: Boolean?,
    val type: String,
    val data: String,
) {
    val isOwned: Boolean get() = description.contains(PHONE_HEARTBEAT_OWNER_MARKER)
}

internal fun scenarioBody(
    binding: HealthDeviceBinding,
    target: HealthTarget,
    active: Boolean = true,
): JsonObject = buildJsonObject {
    put("name", PHONE_HEARTBEAT_SCENARIO_NAME)
    put(
        "desc",
        "$PHONE_HEARTBEAT_OWNER_MARKER Автоматический контроль SprutHub Helper. " +
            "Не удаляйте: приложение безопасно восстановит сценарий при следующей проверке.",
    )
    put("active", active)
    put("onStart", true)
    put("sync", false)
    put("type", "BLOCK")
    put("data", heartbeatScenarioData(binding, target).toString())
}

internal fun heartbeatScenarioData(binding: HealthDeviceBinding, target: HealthTarget): JsonObject = buildJsonObject {
    put("targets", buildJsonArray {
        add(buildJsonObject {
            put("type", "if")
            put("mode", "EVERY")
            put("if", buildJsonObject {
                put("type", "condition")
                put("mode", "OR")
                put("conditions", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "characteristic")
                        put("aId", binding.accessoryId.toLong())
                        put("sId", target.serviceId.toLong())
                        put("cId", target.characteristicId.toLong())
                        put("hs", target.serviceType.ifBlank { "C_Option" })
                        put("hc", target.characteristicType.ifBlank { "C_GenericInteger" })
                        put("trigger", true)
                        put("cond", "")
                        put("value", "")
                        put("timeCond", "")
                        put("time", 0)
                    })
                })
            })
            put("then", buildJsonArray {
                add(buildJsonObject {
                    put("type", "delay")
                    put("index", 1)
                    put("mode", "RESET")
                    put("time", PHONE_HEARTBEAT_TIMEOUT_MS)
                    put("targets", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "notify")
                            put(
                                "text",
                                "SprutHub Helper: телефон не синхронизировался больше 45 минут. " +
                                    "Откройте приложение и проверьте сеть, разрешения и ограничения батареи.",
                            )
                            put("mode", "PUSH")
                            put("to", JsonNull)
                        })
                        add(buildJsonObject {
                            put("type", "notify")
                            put(
                                "text",
                                "SprutHub Helper: контроль телефона просрочен больше чем на 45 минут.",
                            )
                            put("mode", "MESSAGE")
                        })
                    })
                })
            })
            put("else", JsonNull)
        })
    })
}

internal fun heartbeatScenarioIsCurrent(
    scenario: ScenarioRecord,
    binding: HealthDeviceBinding,
    target: HealthTarget,
): Boolean {
    if (!scenario.isOwned || scenario.active != true || scenario.onStart == false) return false
    if (scenario.type.isNotBlank() && scenario.type.uppercase() !in setOf("BLOCK", "2")) return false
    return heartbeatScenarioHasCurrentTarget(scenario, binding, target)
}

private fun heartbeatScenarioHasCurrentTarget(
    scenario: ScenarioRecord,
    binding: HealthDeviceBinding,
    target: HealthTarget,
): Boolean {
    if (!scenario.isOwned) return false
    if (scenario.type.isNotBlank() && scenario.type.uppercase() !in setOf("BLOCK", "2")) return false
    val root = runCatching { Json.parseToJsonElement(scenario.data) }.getOrNull() ?: return false
    var matchingTrigger = false
    var resetDelay = false
    var push = false

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                when (element["type"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
                    "characteristic" -> {
                        matchingTrigger = matchingTrigger || (
                            element.numeric("aId") == binding.accessoryId.toLongOrNull() &&
                                element.numeric("sId") == target.serviceId.toLongOrNull() &&
                                element.numeric("cId") == target.characteristicId.toLongOrNull() &&
                                element["trigger"]?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
                            )
                    }
                    "delay" -> {
                        resetDelay = resetDelay || (
                            element["mode"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "RESET" &&
                                element.numeric("time") == PHONE_HEARTBEAT_TIMEOUT_MS
                            )
                    }
                    "notify" -> {
                        push = push || element["mode"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "PUSH"
                    }
                }
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }
    visit(root)
    return matchingTrigger && resetDelay && push
}

private fun JsonObject.numeric(key: String): Long? =
    (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
