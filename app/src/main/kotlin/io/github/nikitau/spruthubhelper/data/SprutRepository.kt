package io.github.nikitau.spruthubhelper.data

import android.util.Log
import io.github.nikitau.spruthubhelper.sprut.CharacteristicUpdate
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import io.github.nikitau.spruthubhelper.sprut.SprutRpcClient
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

class SprutRepository(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
    private val parser: SprutCatalogParser,
    private val cache: CatalogCache,
    private val scope: CoroutineScope,
) {
    private val refreshMutex = Mutex()
    private val _catalog = MutableStateFlow(SprutCatalog())
    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    private val _diagnostics = MutableStateFlow<List<DiagnosticEvent>>(emptyList())

    val catalog: StateFlow<SprutCatalog> = _catalog.asStateFlow()
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    val diagnostics: StateFlow<List<DiagnosticEvent>> = _diagnostics.asStateFlow()
    val tileAssignments: StateFlow<List<TileAssignment>> = settings.tileAssignments.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    init {
        scope.launch {
            val cached = cache.read()
            if (cached.controls.isNotEmpty()) {
                _catalog.value = cached
                log("Загружен локальный кэш: ${cached.controls.size} элементов")
            }
        }
        scope.launch {
            client.events.collect { event ->
                parser.parseUpdate(event)?.let(::applyUpdate)
            }
        }
    }

    suspend fun refresh(forceConnection: Boolean = false): Result<SprutCatalog> = refreshMutex.withLock {
        _connectionStatus.value = ConnectionStatus(
            phase = ConnectionPhase.CONNECTING,
            message = "Подключение к SprutHub…",
        )
        val config = settings.currentConfig()
        runCatching {
            val endpoint = client.connect(config, force = forceConnection)
            val versionResponse = runCatching { client.call(config, request("server", "version")) }
                .recoverCatching { client.call(config, request("hub", "list")) }
                .getOrDefault(JsonNull)
            val rooms = client.call(config, request("room", "list"))
            val accessories = client.call(
                config,
                request(
                    section = "accessory",
                    operation = "list",
                    operationBody = buildJsonObject { put("expand", "services+characteristics") },
                ),
            )
            val scenarios = runCatching { client.call(config, request("scenario", "list")) }
                .getOrDefault(JsonNull)
            val parsed = parser.parse(
                roomsResponse = rooms,
                accessoriesResponse = accessories,
                scenariosResponse = scenarios,
                hubVersion = findVersion(versionResponse),
            )
            check(parsed.controls.isNotEmpty()) {
                "SprutHub ответил, но управляемые устройства не найдены"
            }
            reconcileTileAssignments(_catalog.value.controls, parsed.controls)
            _catalog.value = parsed
            cache.write(parsed)
            _connectionStatus.value = ConnectionStatus(
                phase = if (endpoint.isLocal) ConnectionPhase.CONNECTED_LOCAL else ConnectionPhase.CONNECTED_CLOUD,
                endpoint = endpoint.url,
                message = if (endpoint.isLocal) "Подключено напрямую по Wi‑Fi" else "Подключено через облако SprutHub",
                lastSuccessEpochMs = System.currentTimeMillis(),
            )
            log("Каталог обновлён: ${parsed.controls.size} элементов")
            parsed
        }.onFailure { error ->
            _connectionStatus.value = ConnectionStatus(
                phase = ConnectionPhase.ERROR,
                message = friendlyError(error),
            )
            log(friendlyError(error), isError = true)
        }
    }

    suspend fun refreshIfStale(maxAgeMs: Long = 30_000): Result<SprutCatalog> {
        val current = _catalog.value
        return if (current.controls.isNotEmpty() && System.currentTimeMillis() - current.refreshedAtEpochMs < maxAgeMs) {
            Result.success(current)
        } else {
            refresh()
        }
    }

    suspend fun setBoolean(controlId: String, value: Boolean): Result<Unit> = perform(controlId) { control, config ->
        updateCharacteristic(
            config = config,
            control = control,
            characteristicId = control.characteristicId,
            field = control.valueField,
            value = booleanWireValue(control.valueField, value),
        )
        updateOptimistically(control.id, control.value.copy(boolValue = value))
    }

    suspend fun setRange(controlId: String, value: Double): Result<Unit> = perform(controlId) { control, config ->
        val bounded = value.coerceIn(control.minimum, control.maximum)
        val field = if (control.behavior == ControlBehavior.TOGGLE_RANGE) control.rangeValueField else control.valueField
        val characteristicId = control.rangeCharacteristicId ?: control.characteristicId
        val wireValue = when (field) {
            "intValue", "longValue", "uintValue" -> JsonPrimitive(bounded.roundToLong())
            else -> JsonPrimitive(bounded)
        }
        updateCharacteristic(config, control, characteristicId, field, wireValue)
        updateOptimistically(control.id, control.value.copy(numberValue = bounded))
    }

    suspend fun execute(controlId: String): Result<Unit> = perform(controlId) { control, config ->
        if (control.kind == DeviceKind.SCENE) {
            client.call(
                config,
                request(
                    section = "scenario",
                    operation = "run",
                    operationBody = buildJsonObject { put("index", control.characteristicId) },
                ),
            )
        } else {
            updateCharacteristic(
                config,
                control,
                control.characteristicId,
                control.valueField,
                booleanWireValue(control.valueField, true),
            )
        }
    }

    suspend fun assignTile(slot: Int, controlId: String): Result<Unit> = runCatching {
        check(_catalog.value.controls.any { it.id == controlId }) { "Устройство не найдено" }
        settings.assignTile(slot, controlId)
        log("${_catalog.value.controls.first { it.id == controlId }.title} назначено в плитку $slot")
    }

    suspend fun clearTile(slot: Int): Result<Unit> = runCatching {
        settings.clearTile(slot)
        log("Плитка $slot освобождена")
    }

    suspend fun reconnectAfterSettingsChange() {
        client.disconnect()
        _connectionStatus.value = ConnectionStatus(message = "Настройки сохранены — выполните проверку")
    }

    private suspend fun perform(
        controlId: String,
        block: suspend (SprutControl, HubConfig) -> Unit,
    ): Result<Unit> = runCatching {
        if (_catalog.value.controls.isEmpty()) refreshIfStale().getOrThrow()
        val control = _catalog.value.controls.firstOrNull { it.id == controlId }
            ?: error("Элемент больше не найден в SprutHub")
        check(control.writable) { "Элемент доступен только для чтения" }
        block(control, settings.currentConfig())
        log("Команда отправлена: ${control.title}")
    }.onFailure { error ->
        log("Команда не выполнена: ${friendlyError(error)}", isError = true)
    }

    private suspend fun updateCharacteristic(
        config: HubConfig,
        control: SprutControl,
        characteristicId: String,
        field: String,
        value: JsonPrimitive,
    ) {
        val body = buildJsonObject {
            put("aId", scalarId(control.accessoryId))
            put("sId", scalarId(control.serviceId))
            put("cId", scalarId(characteristicId))
            put("control", buildJsonObject {
                put("value", buildJsonObject { put(field, value) })
            })
        }
        client.call(config, request("characteristic", "update", body))
    }

    private fun request(
        section: String,
        operation: String,
        operationBody: JsonObject = buildJsonObject {},
    ): JsonObject = buildJsonObject {
        put(section, buildJsonObject { put(operation, operationBody) })
    }

    private fun scalarId(value: String): JsonPrimitive = value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)

    private fun booleanWireValue(field: String, value: Boolean): JsonPrimitive = when (field) {
        "intValue", "longValue", "uintValue", "floatValue", "doubleValue" -> JsonPrimitive(if (value) 1 else 0)
        "stringValue", "enumValue" -> JsonPrimitive(if (value) "1" else "0")
        else -> JsonPrimitive(value)
    }

    private suspend fun reconcileTileAssignments(
        previousControls: List<SprutControl>,
        currentControls: List<SprutControl>,
    ) {
        val validIds = currentControls.mapTo(mutableSetOf(), SprutControl::id)
        val assignedIds = settings.tileAssignments.first().map(TileAssignment::controlId)
        val replacements = assignedIds
            .filterNot(validIds::contains)
            .mapNotNull { oldId ->
                val old = previousControls.firstOrNull { it.id == oldId }
                val accessoryId = old?.accessoryId
                    ?: oldId.substringBefore(':').takeIf { oldId.count { character -> character == ':' } >= 2 }
                    ?: return@mapNotNull null
                val replacement = currentControls
                    .filter { it.accessoryId == accessoryId && it.writable }
                    .maxByOrNull { candidate -> replacementScore(old, candidate) }
                    ?: return@mapNotNull null
                oldId to replacement.id
            }
            .toMap()
        settings.reconcileTileAssignments(validIds, replacements)
        replacements.forEach { (oldId, newId) ->
            Log.i(LOG_TAG, "Tile assignment migrated: $oldId -> $newId")
        }
    }

    private fun replacementScore(old: SprutControl?, candidate: SprutControl): Int =
        (if (old?.serviceId == candidate.serviceId) 200 else 0) +
            (if (old?.kind == candidate.kind && candidate.kind != DeviceKind.OTHER) 120 else 0) +
            (if (old?.title == candidate.title) 40 else 0) +
            when (candidate.behavior) {
                ControlBehavior.TOGGLE_RANGE -> 90
                ControlBehavior.TOGGLE -> 80
                ControlBehavior.BUTTON -> 40
                ControlBehavior.RANGE -> 25
                ControlBehavior.SENSOR -> 0
            } +
            when (candidate.kind) {
                DeviceKind.OTHER, DeviceKind.SENSOR -> 0
                else -> 30
            }

    private fun applyUpdate(update: CharacteristicUpdate) {
        val current = _catalog.value
        var changed = false
        val controls = current.controls.map { control ->
            if (control.accessoryId != update.accessoryId || control.serviceId != update.serviceId) return@map control
            when (update.characteristicId) {
                control.characteristicId -> {
                    changed = true
                    val toggleUpdate = control.behavior == ControlBehavior.TOGGLE ||
                        control.behavior == ControlBehavior.TOGGLE_RANGE
                    val merged = SprutValue(
                        boolValue = update.value.boolValue
                            ?: if (toggleUpdate) update.value.numberValue?.let { it > 0.0 } else null
                            ?: control.value.boolValue,
                        numberValue = update.value.numberValue ?: control.value.numberValue,
                        stringValue = update.value.stringValue ?: control.value.stringValue,
                    )
                    control.copy(value = merged)
                }
                control.rangeCharacteristicId -> {
                    changed = true
                    control.copy(value = control.value.copy(numberValue = update.value.numberValue))
                }
                else -> control
            }
        }
        if (changed) _catalog.value = current.copy(controls = controls, refreshedAtEpochMs = System.currentTimeMillis())
    }

    private fun updateOptimistically(controlId: String, value: SprutValue) {
        val current = _catalog.value
        _catalog.value = current.copy(
            controls = current.controls.map { if (it.id == controlId) it.copy(value = value) else it },
            refreshedAtEpochMs = System.currentTimeMillis(),
        )
    }

    private fun findVersion(element: JsonElement): String {
        fun search(current: JsonElement): String? = when (current) {
            is JsonObject -> current.entries.firstOrNull {
                it.key.equals("version", true) && it.value is JsonPrimitive
            }?.value?.let { (it as JsonPrimitive).contentOrNull }
                ?: current.values.firstNotNullOfOrNull(::search)
            is kotlinx.serialization.json.JsonArray -> current.firstNotNullOfOrNull(::search)
            else -> null
        }
        return search(element).orEmpty()
    }

    private fun friendlyError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("timeout", true) -> "SprutHub не ответил вовремя"
            raw.contains("Unable to resolve", true) -> "Не удалось найти сервер SprutHub"
            raw.contains("failed to connect", true) -> "SprutHub недоступен в локальной сети и облаке"
            raw.isNotBlank() -> raw.take(220)
            else -> "Неизвестная ошибка подключения"
        }
    }

    private fun log(message: String, isError: Boolean = false) {
        if (isError) Log.e(LOG_TAG, message) else Log.i(LOG_TAG, message)
        _diagnostics.value = (listOf(DiagnosticEvent(message = message, isError = isError)) + _diagnostics.value).take(40)
    }

    private companion object {
        const val LOG_TAG = "SprutHubHelper"
    }
}
