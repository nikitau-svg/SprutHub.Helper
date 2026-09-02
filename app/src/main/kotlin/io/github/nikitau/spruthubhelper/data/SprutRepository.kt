package io.github.nikitau.spruthubhelper.data

import android.util.Log
import io.github.nikitau.spruthubhelper.sprut.CharacteristicUpdate
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogLoader
import io.github.nikitau.spruthubhelper.sprut.SprutCatalogParser
import io.github.nikitau.spruthubhelper.sprut.SprutRpcClient
import io.github.nikitau.spruthubhelper.sprut.SprutTransportPhase
import io.github.nikitau.spruthubhelper.sprut.SprutTransportStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun mergeControlUpdate(
    control: SprutControl,
    characteristicId: String,
    update: SprutValue,
): SprutControl? = when (characteristicId) {
    control.characteristicId -> {
        val toggleUpdate = control.behavior == ControlBehavior.TOGGLE ||
            control.behavior == ControlBehavior.TOGGLE_RANGE
        val keepsIndependentRange = control.behavior == ControlBehavior.TOGGLE_RANGE &&
            control.rangeCharacteristicId != null
        control.copy(
            value = SprutValue(
                boolValue = update.boolValue
                    ?: if (toggleUpdate) update.numberValue?.let { it > 0.0 } else null
                    ?: control.value.boolValue,
                numberValue = if (keepsIndependentRange) {
                    control.value.numberValue
                } else {
                    update.numberValue ?: control.value.numberValue
                },
                stringValue = update.stringValue ?: control.value.stringValue,
            ),
        )
    }
    control.rangeCharacteristicId -> control.copy(
        value = control.value.copy(
            numberValue = update.numberValue ?: control.value.numberValue,
        ),
    )
    else -> null
}

internal fun optionWireValue(field: String, target: SprutValue): JsonPrimitive = when (field) {
    "boolValue" -> JsonPrimitive(target.boolValue ?: error("Некорректное логическое значение"))
    "intValue", "longValue", "uintValue" ->
        JsonPrimitive(target.numberValue?.roundToLong() ?: error("Некорректное числовое значение"))
    "floatValue", "doubleValue" ->
        JsonPrimitive(target.numberValue ?: error("Некорректное числовое значение"))
    else -> JsonPrimitive(target.stringValue ?: error("Некорректное текстовое значение"))
}

internal fun preserveConcurrentControlValues(
    parsedControls: List<SprutControl>,
    currentControls: List<SprutControl>,
    authoritativeAtStart: Map<String, Long>,
    currentVersions: Map<String, Long>,
): List<SprutControl> {
    val currentById = currentControls.associateBy(SprutControl::id)
    return parsedControls.map { parsedControl ->
        val changedDuringRefresh = (currentVersions[parsedControl.id] ?: 0L) >
            (authoritativeAtStart[parsedControl.id] ?: 0L)
        val current = currentById[parsedControl.id]
        if (changedDuringRefresh && current != null) {
            parsedControl.copy(value = current.value)
        } else {
            parsedControl
        }
    }
}

class SprutRepository(
    private val settings: SettingsRepository,
    private val client: SprutRpcClient,
    parser: SprutCatalogParser,
    private val cache: CatalogCache,
    private val scope: CoroutineScope,
    private val catalogLoader: SprutCatalogLoader = SprutCatalogLoader(client, parser),
) {
    private val refreshMutex = Mutex()
    private val staleRefreshMutex = Mutex()
    private val _catalog = MutableStateFlow(SprutCatalog())
    private val _connectionStatus = MutableStateFlow(ConnectionStatus())
    private val _diagnostics = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    private val _pendingControlIds = MutableStateFlow<Set<String>>(emptySet())
    private val _authoritativeVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val cacheWriteRequests = Channel<Unit>(Channel.CONFLATED)
    private val commandStateLock = Any()
    private val catalogStateLock = Any()
    private val authoritativeStateLock = Any()
    private val catalogRefreshInProgress = AtomicBoolean(false)
    private var authoritativeRevision = 0L

    val catalog: StateFlow<SprutCatalog> = _catalog.asStateFlow()
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
    val diagnostics: StateFlow<List<DiagnosticEvent>> = _diagnostics.asStateFlow()
    val pendingControlIds: StateFlow<Set<String>> = _pendingControlIds.asStateFlow()
    val tileAssignments: StateFlow<List<TileAssignment>> = settings.tileAssignments.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val panelItems: StateFlow<List<PanelItem>> = settings.panelItems.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyList(),
    )
    val servicePresentations: StateFlow<List<ServicePresentationPreference>> =
        settings.servicePresentations.stateIn(
            scope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Installs an in-memory state for the isolated screenshot build.
     *
     * The production application never calls this hook. Keeping the injection
     * below the UI lets the screenshot variant exercise the real cards,
     * grouping, icons and freshness labels without connecting to a user's hub.
     */
    internal fun installScreenshotState(
        catalog: SprutCatalog,
        connection: ConnectionStatus,
    ) {
        synchronized(catalogStateLock) {
            _catalog.value = catalog
            _connectionStatus.value = connection
            markAuthoritative(catalog.controls.map(SprutControl::id))
        }
    }

    init {
        scope.launch {
            val cached = cache.read()
            if (
                cached.controls.isNotEmpty() &&
                cached.refreshedAtEpochMs > _catalog.value.refreshedAtEpochMs
            ) {
                _catalog.value = cached
                log("Загружен локальный кэш: ${cached.controls.size} элементов")
            }
        }
        scope.launch {
            client.events.collect { event ->
                parser.parseUpdates(event).forEach(::applyUpdate)
            }
        }
        scope.launch {
            client.transportStatus.collect { transport ->
                val current = _connectionStatus.value
                val transportConnected = transport.phase == SprutTransportPhase.CONNECTED_LOCAL ||
                    transport.phase == SprutTransportPhase.CONNECTED_CLOUD
                val next = if (catalogRefreshInProgress.get() && transportConnected) {
                    current.copy(
                        phase = ConnectionPhase.CONNECTING,
                        endpoint = transport.endpoint,
                        message = "Загрузка актуального каталога…",
                    )
                } else {
                    connectionStatusFromTransport(transport, current)
                }
                _connectionStatus.value = next
                when {
                    transport.phase == SprutTransportPhase.ERROR && current.phase != ConnectionPhase.ERROR ->
                        log("Соединение со SprutHub потеряно: ${next.message}", isError = true)
                    (transport.phase == SprutTransportPhase.CONNECTED_LOCAL ||
                        transport.phase == SprutTransportPhase.CONNECTED_CLOUD) &&
                        current.phase == ConnectionPhase.ERROR ->
                        log("Соединение со SprutHub восстановлено")
                }
            }
        }
        scope.launch {
            for (ignored in cacheWriteRequests) {
                delay(CACHE_WRITE_DEBOUNCE_MS)
                while (cacheWriteRequests.tryReceive().isSuccess) {
                    // Collapse a burst of characteristic events into one atomic write.
                }
                cache.write(_catalog.value)
            }
        }
    }

    suspend fun refresh(forceConnection: Boolean = false): Result<SprutCatalog> = refreshMutex.withLock {
        refreshLocked(settings.currentConfig(), forceConnection)
    }

    /**
     * Reconnects the shared Android-interface catalog after the default
     * network returns. A tile or panel refresh that already completed after
     * [recoveryBoundaryEpochMs] wins, avoiding a second queued catalog read.
     */
    internal suspend fun refreshAfterNetworkRecovery(
        recoveryBoundaryEpochMs: Long,
    ): Result<SprutCatalog> = refreshMutex.withLock {
        val connection = _connectionStatus.value
        val alreadyRecovered = catalogRecoveredAfter(
            connection = connection,
            catalog = _catalog.value,
            recoveryBoundaryEpochMs = recoveryBoundaryEpochMs,
        )
        if (alreadyRecovered) {
            Result.success(_catalog.value)
        } else {
            refreshLocked(settings.currentConfig(), forceConnection = true)
        }
    }

    /**
     * Verifies a candidate configuration before it is persisted. A successful
     * verification persists it through [onVerified] before the live socket,
     * assignments and authoritative catalog are adopted. A failed persistence
     * therefore cannot reconcile Android interfaces against the wrong hub.
     */
    suspend fun verifyConfig(
        config: HubConfig,
        onVerified: suspend () -> Unit,
    ): Result<SprutCatalog> = refreshMutex.withLock {
        refreshLocked(config, forceConnection = true, beforeAdopt = onVerified)
    }

    private suspend fun refreshLocked(
        config: HubConfig,
        forceConnection: Boolean,
        beforeAdopt: suspend () -> Unit = {},
    ): Result<SprutCatalog> {
        val connectionBeforeRefresh = _connectionStatus.value
        catalogRefreshInProgress.set(true)
        _connectionStatus.value = ConnectionStatus(
            phase = ConnectionPhase.CONNECTING,
            message = "Подключение к SprutHub…",
        )
        val authoritativeAtStart = _authoritativeVersions.value
        return try {
            runCatching {
                val loaded = catalogLoader.load(config, forceConnection)
                val endpoint = loaded.endpoint
                val parsedSnapshot = loaded.catalog
                check(parsedSnapshot.controls.isNotEmpty()) {
                    "SprutHub ответил, но управляемые устройства не найдены"
                }
                beforeAdopt()
                reconcileAssignments(_catalog.value.controls, parsedSnapshot.controls)
                val parsed = synchronized(catalogStateLock) {
                    val merged = preserveConcurrentUpdates(parsedSnapshot, authoritativeAtStart)
                    _catalog.value = merged
                    markAuthoritative(merged.controls.map(SprutControl::id))
                    merged
                }
                cache.write(parsed)
                _connectionStatus.value = ConnectionStatus(
                    phase = if (endpoint.isLocal) {
                        ConnectionPhase.CONNECTED_LOCAL
                    } else {
                        ConnectionPhase.CONNECTED_CLOUD
                    },
                    endpoint = endpoint.url,
                    message = if (endpoint.isLocal) {
                        "Подключено напрямую по Wi‑Fi"
                    } else {
                        "Подключено через облако SprutHub"
                    },
                    lastSuccessEpochMs = System.currentTimeMillis(),
                )
                log("Каталог обновлён: ${parsed.controls.size} элементов")
                parsed
            }.onFailure { error ->
                if (error is CancellationException) {
                    val wasConnected = connectionBeforeRefresh.phase == ConnectionPhase.CONNECTED_LOCAL ||
                        connectionBeforeRefresh.phase == ConnectionPhase.CONNECTED_CLOUD
                    val transport = client.transportStatus.value
                    val transportConnected = transport.phase == SprutTransportPhase.CONNECTED_LOCAL ||
                        transport.phase == SprutTransportPhase.CONNECTED_CLOUD
                    _connectionStatus.value = if (wasConnected && transportConnected) {
                        connectionStatusFromTransport(transport, connectionBeforeRefresh)
                    } else {
                        ConnectionStatus(message = "Обновление прервано")
                    }
                    throw error
                }
                _connectionStatus.value = ConnectionStatus(
                    phase = ConnectionPhase.ERROR,
                    message = friendlyError(error),
                )
                log(friendlyError(error), isError = true)
            }
        } finally {
            catalogRefreshInProgress.set(false)
        }
    }

    suspend fun refreshIfStale(maxAgeMs: Long = 30_000): Result<SprutCatalog> {
        freshCatalog(maxAgeMs)?.let { return Result.success(it) }
        return staleRefreshMutex.withLock {
            // Several TileService instances can start together when the shade
            // opens. Only the first one performs I/O; the rest reuse it.
            freshCatalog(maxAgeMs)?.let { return@withLock Result.success(it) }
            refresh()
        }
    }

    private fun freshCatalog(maxAgeMs: Long): SprutCatalog? {
        val current = _catalog.value
        val connected = _connectionStatus.value.phase == ConnectionPhase.CONNECTED_LOCAL ||
            _connectionStatus.value.phase == ConnectionPhase.CONNECTED_CLOUD
        return current.takeIf {
            connected &&
                it.controls.isNotEmpty() &&
                System.currentTimeMillis() - it.refreshedAtEpochMs < maxAgeMs
        }
    }

    fun freshness(nowEpochMs: Long = System.currentTimeMillis()): CatalogFreshness =
        CatalogFreshnessPolicy.evaluate(
            catalog = _catalog.value,
            connection = _connectionStatus.value,
            pendingControlIds = _pendingControlIds.value,
            nowEpochMs = nowEpochMs,
        )

    suspend fun setBoolean(controlId: String, value: Boolean): Result<Unit> = perform(controlId) { control, config ->
        writeBoolean(control, config, value)
    }

    /** Derives the opposite value only after the mandatory server freshness check. */
    suspend fun toggleBoolean(controlId: String): Result<Unit> = perform(controlId) { control, config ->
        writeBoolean(control, config, !control.value.asBoolean())
    }

    suspend fun setRange(controlId: String, value: Double): Result<Unit> = perform(controlId) { control, config ->
        writeRange(control, config, value)
    }

    suspend fun setOption(controlId: String, value: SprutValue): Result<Unit> = perform(controlId) { control, config ->
        writeOption(control, config, value)
    }

    /** Chooses the opposite range endpoint only after refreshing the server value. */
    suspend fun toggleRangeEndpoint(controlId: String): Result<Unit> = perform(controlId) { control, config ->
        check(control.behavior == ControlBehavior.RANGE) { "Для устройства требуется открыть регулировку" }
        val midpoint = (control.minimum + control.maximum) / 2.0
        val target = if (control.value.asDouble() > midpoint) control.minimum else control.maximum
        writeRange(control, config, target)
    }

    suspend fun execute(controlId: String): Result<Unit> = perform(controlId) { control, config ->
        check(control.behavior == ControlBehavior.BUTTON) { "Элемент больше не является кнопкой" }
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

    suspend fun clearAllTiles(): Result<Unit> = runCatching {
        settings.clearAllTiles()
        log("Все плитки удалены")
    }

    suspend fun addPanelItem(controlId: String): Result<Unit> = runCatching {
        val card = buildServiceControlCards(_catalog.value.controls).firstOrNull { it.id == controlId }
            ?: error("Устройство не найдено")
        settings.addPanelItem(card.id)
        log("${card.title} добавлено в панель устройств")
    }

    suspend fun removePanelItem(controlId: String): Result<Unit> = runCatching {
        settings.removePanelItem(controlId)
        log("Элемент удалён из крупной панели")
    }

    suspend fun setPanelItemSize(controlId: String, size: PanelItemSize): Result<Unit> = runCatching {
        settings.setPanelItemSize(controlId, size)
        log("Размер элемента крупной панели изменён")
    }

    suspend fun setPanelItemAttributes(controlId: String, attributeControlIds: List<String>?): Result<Unit> =
        runCatching {
            val card = buildServiceControlCards(_catalog.value.controls).firstOrNull { it.id == controlId }
                ?: error("Устройство не найдено")
            val validIds = card.availableAttributes().mapTo(mutableSetOf(), SprutControl::id)
            val selected = attributeControlIds?.filter(validIds::contains)?.take(2)
            settings.setPanelItemAttributes(controlId, selected)
            log("Показатели карточки обновлены")
        }

    suspend fun setServicePresentation(
        cardId: String,
        headlineValueKey: String?,
        secondaryValueKeys: List<String>?,
    ): Result<Unit> = runCatching {
        val card = buildServiceControlCards(_catalog.value.controls).firstOrNull { it.id == cardId }
            ?: error("Сервис больше не найден в каталоге")
        val validKeys = card.characteristicValues()
            .mapTo(linkedSetOf(), CharacteristicDisplayValue::key)
        val normalizedHeadline = headlineValueKey?.takeIf(validKeys::contains)
        check(headlineValueKey == null || normalizedHeadline != null) {
            "Выбранный главный показатель больше недоступен"
        }
        val resolvedHeadlineKey = card.headlineDisplayValue(
            ServicePresentationPreference(card.id, headlineValueKey = normalizedHeadline),
        ).key
        val normalizedSecondary = secondaryValueKeys
            ?.filter(validKeys::contains)
            ?.filterNot { it == resolvedHeadlineKey }
            ?.distinct()
            ?.take(MAX_SERVICE_SECONDARY_VALUES)
        settings.setServicePresentation(card.id, normalizedHeadline, normalizedSecondary)
        log("Отображение сервиса ${card.title} обновлено")
    }

    suspend fun movePanelItem(controlId: String, offset: Int): Result<Unit> = runCatching {
        settings.movePanelItem(controlId, offset)
    }

    suspend fun clearPanelItems(): Result<Unit> = runCatching {
        settings.clearPanelItems()
        log("Крупная панель очищена")
    }

    private suspend fun perform(
        controlId: String,
        block: suspend (SprutControl, HubConfig) -> Unit,
    ): Result<Unit> {
        if (!beginCommand(controlId)) {
            return Result.failure(IllegalStateException("Предыдущая команда ещё подтверждается SprutHub"))
        }
        return try {
            runCatching {
                refreshIfStale(CatalogFreshnessPolicy.COMMAND_MAX_AGE_MS).getOrThrow()
                val control = _catalog.value.controls.firstOrNull { it.id == controlId }
                    ?: error("Элемент больше не найден в SprutHub")
                check(control.writable) { "Элемент доступен только для чтения" }
                block(control, settings.currentConfig())
                log("Команда подтверждена SprutHub: ${control.title}")
            }.onFailure { error ->
                if (error is CancellationException) throw error
                log("Команда не выполнена: ${friendlyError(error)}", isError = true)
            }
        } finally {
            finishCommand(controlId)
        }
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

    private suspend fun writeBoolean(control: SprutControl, config: HubConfig, value: Boolean) {
        check(
            control.behavior == ControlBehavior.TOGGLE || control.behavior == ControlBehavior.TOGGLE_RANGE,
        ) { "Элемент больше не является переключателем" }
        if (control.value.asBooleanOrNull() == value) return
        val baseline = authoritativeVersion(control.id)
        updateCharacteristic(
            config = config,
            control = control,
            characteristicId = control.characteristicId,
            field = control.valueField,
            value = booleanWireValue(control.valueField, value),
        )
        confirmCommand(control.id, baseline) { latest -> latest.value.asBooleanOrNull() == value }
    }

    private suspend fun writeRange(control: SprutControl, config: HubConfig, value: Double) {
        check(
            control.behavior == ControlBehavior.RANGE || control.behavior == ControlBehavior.TOGGLE_RANGE,
        ) { "Элемент не поддерживает регулировку" }
        val bounded = value.coerceIn(control.minimum, control.maximum)
        if (abs(control.value.asDouble() - bounded) <= confirmationTolerance(control)) return
        val baseline = authoritativeVersion(control.id)
        val field = if (control.behavior == ControlBehavior.TOGGLE_RANGE) {
            control.rangeValueField
        } else {
            control.valueField
        }
        val characteristicId = control.rangeCharacteristicId ?: control.characteristicId
        val wireValue = when (field) {
            "intValue", "longValue", "uintValue" -> JsonPrimitive(bounded.roundToLong())
            else -> JsonPrimitive(bounded)
        }
        updateCharacteristic(config, control, characteristicId, field, wireValue)
        confirmCommand(control.id, baseline) { latest ->
            abs(latest.value.asDouble() - bounded) <= confirmationTolerance(latest)
        }
    }

    private suspend fun writeOption(control: SprutControl, config: HubConfig, requested: SprutValue) {
        check(control.behavior == ControlBehavior.OPTIONS) { "Элемент больше не поддерживает выбор режима" }
        val target = control.valueOptions.firstOrNull { option -> option.value.sameValueAs(requested) }
            ?.value
            ?: error("SprutHub больше не предлагает этот вариант")
        if (control.value.sameValueAs(target)) return
        val wireValue = optionWireValue(control.valueField, target)
        val baseline = authoritativeVersion(control.id)
        updateCharacteristic(
            config = config,
            control = control,
            characteristicId = control.characteristicId,
            field = control.valueField,
            value = wireValue,
        )
        confirmCommand(control.id, baseline) { latest -> latest.value.sameValueAs(target) }
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

    private suspend fun reconcileAssignments(
        previousControls: List<SprutControl>,
        currentControls: List<SprutControl>,
    ) {
        val validIds = currentControls.mapTo(mutableSetOf(), SprutControl::id)
        val tileIds = settings.tileAssignments.first().map(TileAssignment::controlId).distinct()
        val tileReplacements = tileIds
            .filterNot(validIds::contains)
            .mapNotNull { oldId ->
                val old = previousControls.firstOrNull { it.id == oldId }
                val accessoryId = old?.accessoryId
                    ?: oldId.substringBefore(':').takeIf { oldId.count { character -> character == ':' } >= 2 }
                    ?: return@mapNotNull null
                val scoredReplacement = currentControls
                    .filter { it.accessoryId == accessoryId && it.writable }
                    .map { candidate -> candidate to replacementScore(old, candidate) }
                    .maxByOrNull { (_, score) -> score }
                    ?: return@mapNotNull null
                val (replacement, score) = scoredReplacement
                if (score < MIN_REPLACEMENT_SCORE) return@mapNotNull null
                oldId to replacement.id
            }
            .toMap()
        settings.reconcileTileAssignments(validIds, tileReplacements)

        val currentCards = buildServiceControlCards(currentControls)
        val previousCards = buildServiceControlCards(previousControls)
        val validCardIds = currentCards.mapTo(mutableSetOf(), ServiceControlCard::id)
        val panelIds = settings.panelItems.first().map(PanelItem::controlId).distinct()
        val panelReplacements = panelIds
            .filterNot(validCardIds::contains)
            .mapNotNull { oldId ->
                val oldControl = currentControls.firstOrNull { it.id == oldId }
                    ?: previousControls.firstOrNull { it.id == oldId }
                val oldCard = previousCards.firstOrNull { it.id == oldId }
                val accessoryId = oldControl?.accessoryId
                    ?: oldCard?.accessoryId
                    ?: oldId.removePrefix("service:").substringBefore(':').takeIf {
                        oldId.startsWith("service:") && it.isNotBlank()
                    }
                    ?: oldId.substringBefore(':').takeIf {
                        !oldId.startsWith("control:") && oldId.count { character -> character == ':' } >= 2
                    }
                    ?: return@mapNotNull null
                val serviceId = oldControl?.serviceId
                    ?: oldCard?.serviceId
                    ?: oldId.removePrefix("service:").substringAfter(':').substringBefore(':').takeIf {
                        oldId.startsWith("service:") && it.isNotBlank()
                    }
                    ?: oldId.substringAfter(':').substringBefore(':').takeIf(String::isNotBlank)
                val exact = serviceId?.let { currentCards.findCardForService(accessoryId, it) }
                val replacement = exact ?: currentCards
                    .filter { it.accessoryId == accessoryId }
                    .maxByOrNull { candidate -> replacementScore(oldControl ?: oldCard?.primaryControl, candidate.primaryControl) }
                    ?: return@mapNotNull null
                oldId to replacement.id
            }
            .toMap()
        settings.reconcilePanelItems(validCardIds, panelReplacements)

        (tileReplacements + panelReplacements).forEach { (oldId, newId) ->
            Log.i(LOG_TAG, "Android assignment migrated: $oldId -> $newId")
        }
    }

    private fun replacementScore(old: SprutControl?, candidate: SprutControl): Int =
        (if (old?.serviceId == candidate.serviceId) 200 else 0) +
            (if (old?.kind == candidate.kind && candidate.kind != DeviceKind.OTHER) 120 else 0) +
            (if (old?.title == candidate.title) 40 else 0) +
            when (candidate.behavior) {
                ControlBehavior.TOGGLE_RANGE -> 90
                ControlBehavior.TOGGLE -> 80
                ControlBehavior.OPTIONS -> 70
                ControlBehavior.BUTTON -> 40
                ControlBehavior.RANGE -> 25
                ControlBehavior.SENSOR -> 0
            } +
            when (candidate.kind) {
                DeviceKind.OTHER, DeviceKind.SENSOR -> 0
                else -> 30
            }

    private fun applyUpdate(update: CharacteristicUpdate) {
        synchronized(catalogStateLock) {
            val current = _catalog.value
            var changed = false
            val changedControlIds = mutableListOf<String>()
            val controls = current.controls.map { control ->
                if (control.accessoryId != update.accessoryId || control.serviceId != update.serviceId) return@map control
                mergeControlUpdate(control, update.characteristicId, update.value)?.also {
                    changed = true
                    changedControlIds += control.id
                } ?: control
            }
            if (changed) {
                _catalog.value = current.copy(controls = controls, refreshedAtEpochMs = System.currentTimeMillis())
                markAuthoritative(changedControlIds)
                cacheWriteRequests.trySend(Unit)
            }
        }
    }

    private fun preserveConcurrentUpdates(
        parsed: SprutCatalog,
        authoritativeAtStart: Map<String, Long>,
    ): SprutCatalog = parsed.copy(
        controls = preserveConcurrentControlValues(
            parsedControls = parsed.controls,
            currentControls = _catalog.value.controls,
            authoritativeAtStart = authoritativeAtStart,
            currentVersions = _authoritativeVersions.value,
        ),
    )

    private suspend fun confirmCommand(
        controlId: String,
        baselineVersion: Long,
        matches: (SprutControl) -> Boolean,
    ) {
        val eventConfirmed = withTimeoutOrNull(COMMAND_EVENT_TIMEOUT_MS) {
            _authoritativeVersions.first { versions ->
                (versions[controlId] ?: 0L) > baselineVersion &&
                    _catalog.value.controls.firstOrNull { it.id == controlId }?.let(matches) == true
            }
        } != null
        if (eventConfirmed) return

        var lastFailure: Throwable? = null
        COMMAND_READBACK_DELAYS_MS.forEach { readbackDelay ->
            delay(readbackDelay)
            val refreshed = refresh(forceConnection = false)
            lastFailure = refreshed.exceptionOrNull()
            if (refreshed.isSuccess) {
                val confirmed = _catalog.value.controls.firstOrNull { it.id == controlId }
                    ?: error("Элемент исчез из каталога после команды")
                if (matches(confirmed)) return
            }
        }
        lastFailure?.let { throw it }
        error("SprutHub принял команду, но не подтвердил новое состояние")
    }

    private fun authoritativeVersion(controlId: String): Long = _authoritativeVersions.value[controlId] ?: 0L

    private fun markAuthoritative(controlIds: Collection<String>) {
        if (controlIds.isEmpty()) return
        synchronized(authoritativeStateLock) {
            val updated = _authoritativeVersions.value.toMutableMap()
            controlIds.distinct().forEach { controlId ->
                authoritativeRevision += 1L
                updated[controlId] = authoritativeRevision
            }
            _authoritativeVersions.value = updated
        }
    }

    private fun beginCommand(controlId: String): Boolean = synchronized(commandStateLock) {
        if (controlId in _pendingControlIds.value) {
            false
        } else {
            _pendingControlIds.value = _pendingControlIds.value + controlId
            true
        }
    }

    private fun finishCommand(controlId: String) = synchronized(commandStateLock) {
        _pendingControlIds.value = _pendingControlIds.value - controlId
    }

    private fun confirmationTolerance(control: SprutControl): Double = (control.step / 2.0).coerceAtLeast(0.01)

    private fun connectionStatusFromTransport(
        transport: SprutTransportStatus,
        current: ConnectionStatus,
    ): ConnectionStatus = when (transport.phase) {
        SprutTransportPhase.IDLE -> if (
            current.phase == ConnectionPhase.CONNECTED_LOCAL ||
            current.phase == ConnectionPhase.CONNECTED_CLOUD ||
            current.phase == ConnectionPhase.CONNECTING
        ) {
            ConnectionStatus(message = "Соединение закрыто")
        } else {
            current
        }
        SprutTransportPhase.CONNECTING -> current.copy(
            phase = ConnectionPhase.CONNECTING,
            message = "Подключение к SprutHub…",
        )
        SprutTransportPhase.CONNECTED_LOCAL -> ConnectionStatus(
            phase = ConnectionPhase.CONNECTED_LOCAL,
            endpoint = transport.endpoint,
            message = "Подключено напрямую по Wi‑Fi",
            lastSuccessEpochMs = System.currentTimeMillis(),
        )
        SprutTransportPhase.CONNECTED_CLOUD -> ConnectionStatus(
            phase = ConnectionPhase.CONNECTED_CLOUD,
            endpoint = transport.endpoint,
            message = "Подключено через облако SprutHub",
            lastSuccessEpochMs = System.currentTimeMillis(),
        )
        SprutTransportPhase.ERROR -> ConnectionStatus(
            phase = ConnectionPhase.ERROR,
            endpoint = transport.endpoint,
            message = friendlyError(IllegalStateException(transport.message)),
            lastSuccessEpochMs = current.lastSuccessEpochMs,
        )
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
        const val MIN_REPLACEMENT_SCORE = 120
        const val CACHE_WRITE_DEBOUNCE_MS = 250L
        const val COMMAND_EVENT_TIMEOUT_MS = 2_000L
        val COMMAND_READBACK_DELAYS_MS = longArrayOf(300L, 800L)
    }
}
