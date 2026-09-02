package io.github.nikitau.spruthubhelper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URI
import java.util.UUID
import io.github.nikitau.spruthubhelper.presence.PresenceZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "spruthub_helper_settings")
private const val ONBOARDING_IN_PROGRESS_VERSION = -1
private const val CURRENT_ONBOARDING_VERSION = 1

internal fun shouldShowInitialOnboarding(
    storedVersion: Int?,
    storedSerial: String,
): Boolean = when {
    storedVersion == CURRENT_ONBOARDING_VERSION -> false
    storedVersion == ONBOARDING_IN_PROGRESS_VERSION -> true
    storedVersion == null && storedSerial.isNotBlank() -> false
    else -> true
}

internal data class HelperDeviceIdentity(
    val shortId: String,
    val legacyRecoveryAllowed: Boolean,
)

class SettingsRepository(private val context: Context) {
    private val secretStore = SecretStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceIdentityMutex = Mutex()

    val config: Flow<HubConfig> = context.settingsDataStore.data.map(::preferencesToConfig)

    val tileAssignments: Flow<List<TileAssignment>> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.TILE_ASSIGNMENTS]
            ?.let { encoded -> runCatching { json.decodeFromString<List<TileAssignment>>(encoded) }.getOrNull() }
            .orEmpty()
            .filter { it.slot in 1..MAX_TILE_SLOTS }
            .distinctBy { it.slot }
            .sortedBy { it.slot }
    }

    val panelItems: Flow<List<PanelItem>> = context.settingsDataStore.data.map(::decodePanelItems)

    val servicePresentations: Flow<List<ServicePresentationPreference>> =
        context.settingsDataStore.data.map(::decodeServicePresentations)

    val selectedHealthMetrics: Flow<Set<HealthMetric>> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.HEALTH_METRICS]
            ?.split(',')
            ?.mapNotNull { runCatching { HealthMetric.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_HEALTH_METRICS
    }

    val healthBinding: Flow<HealthDeviceBinding?> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.HEALTH_BINDING]
            ?.let { runCatching { json.decodeFromString<HealthDeviceBinding>(it) }.getOrNull() }
    }

    val healthEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[Keys.HEALTH_ENABLED] ?: false }
    val healthUserPaused: Flow<Boolean> = context.settingsDataStore.data.map {
        it[Keys.HEALTH_USER_PAUSED] ?: false
    }
    val lastHealthSync: Flow<Long?> = context.settingsDataStore.data.map { it[Keys.LAST_HEALTH_SYNC] }

    val selectedPhoneSensors: Flow<Set<PhoneSensor>> = context.settingsDataStore.data.map { preferences ->
        val selected = preferences[Keys.PHONE_SENSORS]
            ?.split(',')
            ?.mapNotNull { runCatching { PhoneSensor.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PHONE_SENSORS
        withRequiredPhoneSensors(selected)
    }

    val phoneBinding: Flow<HealthDeviceBinding?> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.PHONE_BINDING]
            ?.let { runCatching { json.decodeFromString<HealthDeviceBinding>(it) }.getOrNull() }
    }

    val phoneSyncSettings: Flow<PhoneSyncSettings> = context.settingsDataStore.data.map { preferences ->
        PhoneSyncSettings(
            enabled = preferences[Keys.PHONE_ENABLED] ?: false,
            mode = preferences[Keys.PHONE_SYNC_MODE]
                ?.let { runCatching { PhoneSyncMode.valueOf(it) }.getOrNull() }
                ?: PhoneSyncMode.BALANCED,
            pollInterval = preferences[Keys.PHONE_POLL_INTERVAL]
                ?.let { runCatching { PhonePollInterval.valueOf(it) }.getOrNull() }
                ?: PhonePollInterval.FIVE_MINUTES,
            watchdogEnabled = preferences[Keys.PHONE_WATCHDOG_ENABLED] ?: true,
        )
    }

    val lastPhoneSync: Flow<Long?> = context.settingsDataStore.data.map { it[Keys.LAST_PHONE_SYNC] }
    val phoneMonitoringStarted: Flow<Long?> = context.settingsDataStore.data.map {
        it[Keys.PHONE_MONITORING_STARTED]
    }
    val phoneWatchdogNotifiedReference: Flow<Long?> = context.settingsDataStore.data.map {
        it[Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE]
    }
    val phoneHeartbeatScenarioIndex: Flow<String?> = context.settingsDataStore.data.map {
        it[Keys.PHONE_HEARTBEAT_SCENARIO_INDEX]
    }

    val presenceZones: Flow<List<PresenceZone>> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.PRESENCE_ZONES]
            ?.let { encoded -> runCatching { json.decodeFromString<List<PresenceZone>>(encoded) }.getOrNull() }
            .orEmpty()
            .distinctBy(PresenceZone::id)
    }

    /**
     * Returns an installation-scoped pseudonymous identifier. It is random,
     * contains no Android or SprutHub serial, and is persisted before a
     * virtual accessory can be created. Existing installations may recover
     * old unsuffixed device names; fresh installations never adopt a
     * legacy accessory that could belong to another identical phone.
     */
    internal suspend fun helperDeviceIdentity(): HelperDeviceIdentity = deviceIdentityMutex.withLock {
        val current = context.settingsDataStore.data.first()
        current[Keys.DEVICE_INSTANCE_ID]?.takeIf(String::isNotBlank)?.let { saved ->
            return@withLock HelperDeviceIdentity(
                shortId = saved,
                legacyRecoveryAllowed = current[Keys.DEVICE_LEGACY_RECOVERY] ?: true,
            )
        }

        val identity = HelperDeviceIdentity(
            shortId = UUID.randomUUID().toString().replace("-", "").take(6).uppercase(),
            legacyRecoveryAllowed = current.asMap().isNotEmpty(),
        )
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.DEVICE_INSTANCE_ID] = identity.shortId
            preferences[Keys.DEVICE_LEGACY_RECOVERY] = identity.legacyRecoveryAllowed
        }
        identity
    }

    suspend fun currentConfig(): HubConfig = config.first()

    /**
     * Decides whether the short first-run flow should be shown for this launch.
     *
     * Installations configured before onboarding existed are migrated silently:
     * their stored hub serial is enough to prove that the user has already gone
     * through the longer connection flow. Fresh installations keep returning
     * `true` until the final onboarding screen is explicitly completed.
     */
    suspend fun prepareOnboardingForLaunch(): Boolean {
        val preferences = context.settingsDataStore.data.first()
        val storedVersion = preferences[Keys.ONBOARDING_VERSION]
        val storedSerial = preferences[Keys.SERIAL].orEmpty()
        val required = shouldShowInitialOnboarding(storedVersion, storedSerial)
        if (required && storedVersion == null) {
            context.settingsDataStore.edit {
                it[Keys.ONBOARDING_VERSION] = ONBOARDING_IN_PROGRESS_VERSION
            }
        } else if (!required && storedVersion != CURRENT_ONBOARDING_VERSION) {
            context.settingsDataStore.edit {
                it[Keys.ONBOARDING_VERSION] = CURRENT_ONBOARDING_VERSION
            }
        }
        return required
    }

    suspend fun markOnboardingComplete() {
        context.settingsDataStore.edit {
            it[Keys.ONBOARDING_VERSION] = CURRENT_ONBOARDING_VERSION
        }
    }

    suspend fun saveConfig(config: HubConfig, passwordUpdate: HubPasswordUpdate) {
        val normalized = normalizeAndValidateHubConfig(config)
        val previous = currentConfig()
        try {
            secretStore.updatePasswords(passwordUpdate)
            writeConfigPreferences(normalized)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { writeConfigPreferences(previous) }
                    .exceptionOrNull()
                    ?.let(error::addSuppressed)
                runCatching {
                    secretStore.updatePasswords(
                        HubPasswordUpdate(
                            localPassword = previous.localPassword,
                            cloudPassword = previous.cloudPassword,
                        ),
                    )
                }.exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private suspend fun writeConfigPreferences(config: HubConfig) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.MODE] = config.mode.name
            preferences[Keys.LOCAL_URL] = config.localUrl
            preferences[Keys.CLOUD_URL] = config.cloudUrl
            preferences[Keys.SERIAL] = config.serial
            preferences[Keys.EMAIL] = config.email
        }
    }

    /**
     * Compatibility bridge for the original single-password UI. A replacement
     * updates both endpoint credentials, matching the behaviour before v2.
     */
    suspend fun saveConfig(config: HubConfig, replacePassword: Boolean) {
        saveConfig(
            config = config,
            passwordUpdate = if (replacePassword) {
                HubPasswordUpdate(
                    localPassword = config.password,
                    cloudPassword = config.password,
                )
            } else {
                HubPasswordUpdate()
            },
        )
    }

    suspend fun assignTile(slot: Int, controlId: String) {
        require(slot in 1..MAX_TILE_SLOTS)
        context.settingsDataStore.edit { preferences ->
            val current = decodeAssignments(preferences)
                .filterNot { it.slot == slot || it.controlId == controlId }
                .plus(TileAssignment(slot, controlId))
                .sortedBy { it.slot }
            preferences[Keys.TILE_ASSIGNMENTS] = json.encodeToString(current)
        }
    }

    suspend fun clearTile(slot: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.TILE_ASSIGNMENTS] = json.encodeToString(
                decodeAssignments(preferences).filterNot { it.slot == slot },
            )
        }
    }

    suspend fun clearAllTiles() {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.TILE_ASSIGNMENTS] = json.encodeToString(emptyList<TileAssignment>())
        }
    }

    suspend fun addPanelItem(controlId: String) {
        require(controlId.isNotBlank()) { "Устройство для панели не выбрано" }
        context.settingsDataStore.edit { preferences ->
            val current = decodePanelItems(preferences)
            if (current.none { it.controlId == controlId }) {
                require(current.size < MAX_PANEL_ITEMS) { "В панели уже $MAX_PANEL_ITEMS карточек" }
                preferences[Keys.PANEL_ITEMS] = json.encodeToString(
                    current + PanelItem(controlId = controlId, size = PanelItemSize.COMPACT),
                )
            }
        }
    }

    suspend fun removePanelItem(controlId: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PANEL_ITEMS] = json.encodeToString(
                decodePanelItems(preferences).filterNot { it.controlId == controlId },
            )
        }
    }

    suspend fun setPanelItemSize(controlId: String, size: PanelItemSize) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PANEL_ITEMS] = json.encodeToString(
                decodePanelItems(preferences).map { item ->
                    if (item.controlId == controlId) item.copy(size = size) else item
                },
            )
        }
    }

    suspend fun setPanelItemAttributes(controlId: String, attributeControlIds: List<String>?) {
        val normalized = attributeControlIds?.filter(String::isNotBlank)?.distinct()?.take(2)
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PANEL_ITEMS] = json.encodeToString(
                decodePanelItems(preferences).map { item ->
                    if (item.controlId == controlId) item.copy(attributeControlIds = normalized) else item
                },
            )
        }
    }

    suspend fun movePanelItem(controlId: String, offset: Int) {
        require(offset == -1 || offset == 1)
        context.settingsDataStore.edit { preferences ->
            val current = decodePanelItems(preferences).toMutableList()
            val from = current.indexOfFirst { it.controlId == controlId }
            if (from < 0) return@edit
            val to = (from + offset).coerceIn(0, current.lastIndex)
            if (from != to) {
                val moved = current.removeAt(from)
                current.add(to, moved)
                preferences[Keys.PANEL_ITEMS] = json.encodeToString(current)
            }
        }
    }

    suspend fun clearPanelItems() {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PANEL_ITEMS] = json.encodeToString(emptyList<PanelItem>())
        }
    }

    suspend fun setServicePresentation(
        cardId: String,
        headlineValueKey: String?,
        secondaryValueKeys: List<String>?,
    ) {
        require(cardId.isNotBlank()) { "Сервис для настройки не выбран" }
        val candidate = ServicePresentationPreference(
            cardId = cardId,
            headlineValueKey = headlineValueKey,
            secondaryValueKeys = secondaryValueKeys,
        )
        context.settingsDataStore.edit { preferences ->
            val current = decodeServicePresentations(preferences).filterNot { it.cardId == cardId }
            val normalized = normalizeServicePresentationPreferences(current + candidate)
            preferences[Keys.SERVICE_PRESENTATIONS] = json.encodeToString(normalized)
        }
    }

    suspend fun reconcileTileAssignments(
        validControlIds: Set<String>,
        replacements: Map<String, String>,
    ) {
        context.settingsDataStore.edit { preferences ->
            val current = decodeAssignments(preferences)
            val reconciled = current.mapNotNull { assignment ->
                when {
                    assignment.controlId in validControlIds -> assignment
                    replacements[assignment.controlId] != null -> assignment.copy(
                        controlId = replacements.getValue(assignment.controlId),
                    )
                    else -> null
                }
            }.distinctBy(TileAssignment::slot).sortedBy(TileAssignment::slot)
            if (reconciled != current) {
                preferences[Keys.TILE_ASSIGNMENTS] = json.encodeToString(reconciled)
            }
        }
    }

    suspend fun reconcilePanelItems(
        validControlIds: Set<String>,
        replacements: Map<String, String>,
    ) {
        context.settingsDataStore.edit { preferences ->
            val current = decodePanelItems(preferences)
            val reconciled = reconcilePanelSelection(current, validControlIds, replacements)
            if (reconciled != current) {
                preferences[Keys.PANEL_ITEMS] = json.encodeToString(reconciled)
            }
        }
    }

    suspend fun saveHealthMetrics(metrics: Set<HealthMetric>) {
        require(metrics.isNotEmpty()) { "Выберите хотя бы один показатель здоровья" }
        context.settingsDataStore.edit { it[Keys.HEALTH_METRICS] = metrics.joinToString(",", transform = HealthMetric::name) }
    }

    suspend fun saveHealthBinding(binding: HealthDeviceBinding) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.HEALTH_BINDING] = json.encodeToString(binding)
        }
    }

    suspend fun setHealthEnabled(enabled: Boolean, userInitiated: Boolean = false) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.HEALTH_ENABLED] = enabled
            when {
                userInitiated -> preferences[Keys.HEALTH_USER_PAUSED] = !enabled
                enabled -> preferences[Keys.HEALTH_USER_PAUSED] = false
            }
        }
    }

    suspend fun setHealthUserPaused(paused: Boolean) {
        context.settingsDataStore.edit { it[Keys.HEALTH_USER_PAUSED] = paused }
    }

    suspend fun markHealthSynced(epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { it[Keys.LAST_HEALTH_SYNC] = epochMs }
    }

    suspend fun savePhoneSensors(sensors: Set<PhoneSensor>) {
        require(sensors.isNotEmpty()) { "Выберите хотя бы один показатель телефона" }
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PHONE_SENSORS] = withRequiredPhoneSensors(sensors)
                .joinToString(",", transform = PhoneSensor::name)
        }
    }

    suspend fun savePhoneBinding(binding: HealthDeviceBinding) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PHONE_BINDING] = json.encodeToString(binding)
        }
    }

    suspend fun clearPhoneBinding() {
        context.settingsDataStore.edit { preferences ->
            preferences.remove(Keys.PHONE_BINDING)
            preferences[Keys.PHONE_ENABLED] = false
            preferences.remove(Keys.PHONE_MONITORING_STARTED)
            preferences.remove(Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE)
        }
    }

    suspend fun setPhoneEnabled(enabled: Boolean, epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            val wasEnabled = preferences[Keys.PHONE_ENABLED] ?: false
            preferences[Keys.PHONE_ENABLED] = enabled
            if (enabled && !wasEnabled) {
                preferences[Keys.PHONE_MONITORING_STARTED] = epochMs
                preferences.remove(Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE)
            } else if (!enabled) {
                preferences.remove(Keys.PHONE_MONITORING_STARTED)
                preferences.remove(Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE)
            }
        }
    }

    suspend fun setPhoneSyncMode(mode: PhoneSyncMode) {
        context.settingsDataStore.edit { it[Keys.PHONE_SYNC_MODE] = mode.name }
    }

    suspend fun setPhonePollInterval(interval: PhonePollInterval) {
        context.settingsDataStore.edit { it[Keys.PHONE_POLL_INTERVAL] = interval.name }
    }

    suspend fun setPhoneWatchdogEnabled(enabled: Boolean, epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PHONE_WATCHDOG_ENABLED] = enabled
            preferences.remove(Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE)
            if (enabled && preferences[Keys.PHONE_ENABLED] == true) {
                preferences[Keys.PHONE_MONITORING_STARTED] = epochMs
            }
        }
    }

    suspend fun ensurePhoneMonitoringStarted(epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            if (
                preferences[Keys.PHONE_ENABLED] == true &&
                preferences[Keys.PHONE_MONITORING_STARTED] == null
            ) {
                preferences[Keys.PHONE_MONITORING_STARTED] = epochMs
            }
        }
    }

    suspend fun markPhoneSynced(epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.LAST_PHONE_SYNC] = epochMs
            preferences.remove(Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE)
        }
    }

    suspend fun markPhoneWatchdogNotified(referenceEpochMs: Long) {
        context.settingsDataStore.edit {
            it[Keys.PHONE_WATCHDOG_NOTIFIED_REFERENCE] = referenceEpochMs
        }
    }

    suspend fun savePhoneHeartbeatScenarioIndex(index: String?) {
        context.settingsDataStore.edit { preferences ->
            if (index.isNullOrBlank()) {
                preferences.remove(Keys.PHONE_HEARTBEAT_SCENARIO_INDEX)
            } else {
                preferences[Keys.PHONE_HEARTBEAT_SCENARIO_INDEX] = index
            }
        }
    }

    suspend fun savePresenceZones(zones: List<PresenceZone>) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.PRESENCE_ZONES] = json.encodeToString(zones.distinctBy(PresenceZone::id))
        }
    }

    suspend fun upsertPresenceZone(zone: PresenceZone) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[Keys.PRESENCE_ZONES]
                ?.let { encoded -> runCatching { json.decodeFromString<List<PresenceZone>>(encoded) }.getOrNull() }
                .orEmpty()
            preferences[Keys.PRESENCE_ZONES] = json.encodeToString(
                (current.filterNot { it.id == zone.id } + zone).distinctBy(PresenceZone::id),
            )
        }
    }

    suspend fun removePresenceZone(id: String) {
        context.settingsDataStore.edit { preferences ->
            val current = preferences[Keys.PRESENCE_ZONES]
                ?.let { encoded -> runCatching { json.decodeFromString<List<PresenceZone>>(encoded) }.getOrNull() }
                .orEmpty()
            preferences[Keys.PRESENCE_ZONES] = json.encodeToString(current.filterNot { it.id == id })
        }
    }

    private fun preferencesToConfig(preferences: Preferences): HubConfig {
        val passwords = secretStore.readPasswords()
        return HubConfig(
            mode = preferences[Keys.MODE]
                ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
                ?: ConnectionMode.AUTO,
            localUrl = preferences[Keys.LOCAL_URL] ?: HubConfig.DEFAULT_LOCAL_URL,
            cloudUrl = preferences[Keys.CLOUD_URL] ?: HubConfig.DEFAULT_CLOUD_URL,
            serial = normalizeHubSerial(preferences[Keys.SERIAL] ?: HubConfig.DEFAULT_SERIAL),
            email = preferences[Keys.EMAIL].orEmpty(),
            localPassword = passwords.localPassword,
            cloudPassword = passwords.cloudPassword,
            password = passwords.legacyCompatiblePassword,
        )
    }

    private fun decodeAssignments(preferences: Preferences): List<TileAssignment> =
        preferences[Keys.TILE_ASSIGNMENTS]
            ?.let { runCatching { json.decodeFromString<List<TileAssignment>>(it) }.getOrNull() }
            .orEmpty()

    private fun decodePanelItems(preferences: Preferences): List<PanelItem> =
        preferences[Keys.PANEL_ITEMS]
            ?.let { runCatching { json.decodeFromString<List<PanelItem>>(it) }.getOrNull() }
            .orEmpty()
            .filter { it.controlId.isNotBlank() }
            .distinctBy(PanelItem::controlId)
            .map { item ->
                item.copy(
                    attributeControlIds = item.attributeControlIds
                        ?.filter(String::isNotBlank)
                        ?.distinct()
                        ?.take(2),
                )
            }
            .take(MAX_PANEL_ITEMS)

    private fun decodeServicePresentations(preferences: Preferences): List<ServicePresentationPreference> =
        preferences[Keys.SERVICE_PRESENTATIONS]
            ?.let { encoded ->
                runCatching { json.decodeFromString<List<ServicePresentationPreference>>(encoded) }.getOrNull()
            }
            .orEmpty()
            .let(::normalizeServicePresentationPreferences)

    private object Keys {
        val DEVICE_INSTANCE_ID = stringPreferencesKey("device_instance_id")
        val DEVICE_LEGACY_RECOVERY = booleanPreferencesKey("device_legacy_recovery")
        val ONBOARDING_VERSION = intPreferencesKey("onboarding_version")
        val MODE = stringPreferencesKey("connection_mode")
        val LOCAL_URL = stringPreferencesKey("local_url")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val SERIAL = stringPreferencesKey("serial")
        val EMAIL = stringPreferencesKey("email")
        val TILE_ASSIGNMENTS = stringPreferencesKey("tile_assignments")
        val PANEL_ITEMS = stringPreferencesKey("panel_items")
        val SERVICE_PRESENTATIONS = stringPreferencesKey("service_presentations")
        val HEALTH_METRICS = stringPreferencesKey("health_metrics")
        val HEALTH_BINDING = stringPreferencesKey("health_binding")
        val HEALTH_ENABLED = booleanPreferencesKey("health_enabled")
        val HEALTH_USER_PAUSED = booleanPreferencesKey("health_user_paused")
        val LAST_HEALTH_SYNC = longPreferencesKey("last_health_sync")
        val PHONE_SENSORS = stringPreferencesKey("phone_sensors")
        val PHONE_BINDING = stringPreferencesKey("phone_binding")
        val PHONE_ENABLED = booleanPreferencesKey("phone_enabled")
        val PHONE_SYNC_MODE = stringPreferencesKey("phone_sync_mode")
        val PHONE_POLL_INTERVAL = stringPreferencesKey("phone_poll_interval")
        val PHONE_WATCHDOG_ENABLED = booleanPreferencesKey("phone_watchdog_enabled")
        val LAST_PHONE_SYNC = longPreferencesKey("last_phone_sync")
        val PHONE_MONITORING_STARTED = longPreferencesKey("phone_monitoring_started")
        val PHONE_WATCHDOG_NOTIFIED_REFERENCE = longPreferencesKey("phone_watchdog_notified_reference")
        val PHONE_HEARTBEAT_SCENARIO_INDEX = stringPreferencesKey("phone_heartbeat_scenario_index")
        val PRESENCE_ZONES = stringPreferencesKey("presence_zones")
    }

    companion object {
        const val MAX_TILE_SLOTS = 12
        const val MAX_PANEL_ITEMS = 48
        val DEFAULT_HEALTH_METRICS = setOf(
            HealthMetric.STEPS,
            HealthMetric.HEART_RATE,
            HealthMetric.SLEEP,
            HealthMetric.WEIGHT,
            HealthMetric.OXYGEN_SATURATION,
            HealthMetric.ACTIVE_CALORIES,
        )
        val DEFAULT_PHONE_SENSORS = setOf(
            PhoneSensor.BATTERY_LEVEL,
            PhoneSensor.IS_CHARGING,
            PhoneSensor.CHARGER_TYPE,
            PhoneSensor.BATTERY_TEMPERATURE,
            PhoneSensor.POWER_SAVE_MODE,
            PhoneSensor.CONNECTION_TYPE,
            PhoneSensor.NETWORK_VALIDATED,
            PhoneSensor.DEVICE_MODEL,
            PhoneSensor.ANDROID_VERSION,
            PhoneSensor.SCREEN_INTERACTIVE,
            PhoneSensor.SYNC_HEARTBEAT,
            PhoneSensor.LAST_SYNC,
        )
    }
}

internal fun normalizeAndValidateHubConfig(config: HubConfig): HubConfig {
    val normalized = config.copy(
        localUrl = normalizeSprutEndpoint(config.localUrl, secureByDefault = false),
        cloudUrl = normalizeSprutEndpoint(config.cloudUrl, secureByDefault = true),
        serial = normalizeHubSerial(config.serial),
        email = config.email.trim(),
    )
    require(normalized.serial.isNotBlank()) { "Укажите серийный номер SprutHub" }
    when (normalized.mode) {
        ConnectionMode.AUTO -> require(normalized.localUrl.isNotBlank() || normalized.cloudUrl.isNotBlank()) {
            "Укажите хотя бы один адрес SprutHub"
        }
        ConnectionMode.LOCAL -> require(normalized.localUrl.isNotBlank()) { "Укажите локальный адрес SprutHub" }
        ConnectionMode.CLOUD -> require(normalized.cloudUrl.isNotBlank()) { "Укажите облачный адрес SprutHub" }
    }
    listOf(normalized.localUrl to "локальный", normalized.cloudUrl to "облачный")
        .filter { (url) -> url.isNotBlank() }
        .forEach { (url, label) ->
            val uri = runCatching { URI(url) }.getOrNull()
                ?: error("Некорректный $label адрес")
            require(uri.scheme == "ws" || uri.scheme == "wss") {
                "$label адрес должен начинаться с ws:// или wss://"
            }
            require(uri.host != null) { "В $label адресе не найден хост" }
            if (uri.scheme == "ws") {
                require(label == "локальный" && isPrivateLanHost(uri.host)) {
                    "Незашифрованный ws:// разрешён только для локального адреса; используйте wss://"
                }
            }
        }
    return normalized
}

internal fun normalizeSprutEndpoint(raw: String, secureByDefault: Boolean): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "wss://${trimmed.substringAfter("://")}"
        trimmed.startsWith("http://", ignoreCase = true) -> "ws://${trimmed.substringAfter("://")}"
        "://" !in trimmed -> "${if (secureByDefault) "wss" else "ws"}://$trimmed"
        else -> trimmed
    }
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return withScheme
    val path = uri.rawPath.takeUnless { it.isNullOrBlank() || it == "/" } ?: "/spruthub"
    return runCatching {
        URI(uri.scheme?.lowercase(), uri.rawUserInfo, uri.host, uri.port, path, uri.rawQuery, uri.rawFragment)
            .toASCIIString()
    }.getOrDefault(withScheme)
}

/** Repairs the accidental "serial + serial" value produced by early builds. */
internal fun normalizeHubSerial(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length != 32) return trimmed

    val first = trimmed.substring(0, 16)
    val second = trimmed.substring(16)
    return if (
        first.matches(Regex("[0-9a-fA-F]{16}")) &&
        first.equals(second, ignoreCase = true)
    ) {
        first
    } else {
        trimmed
    }
}

internal fun isPrivateLanHost(rawHost: String): Boolean {
    val host = rawHost.trim('[', ']').lowercase()
    if (host == "localhost" || host == "::1" || host.endsWith(".local") || '.' !in host && ':' !in host) return true
    if (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
        host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
    ) return true
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        octets[0] == 169 && octets[1] == 254 ||
        octets[0] == 172 && octets[1] in 16..31 ||
        octets[0] == 192 && octets[1] == 168 ||
        octets[0] == 100 && octets[1] in 64..127
}
