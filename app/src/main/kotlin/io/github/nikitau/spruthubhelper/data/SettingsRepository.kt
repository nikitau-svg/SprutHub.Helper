package io.github.nikitau.spruthubhelper.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore(name = "spruthub_helper_settings")

class SettingsRepository(private val context: Context) {
    private val secretStore = SecretStore(context)
    private val json = Json { ignoreUnknownKeys = true }

    val config: Flow<HubConfig> = context.settingsDataStore.data.map(::preferencesToConfig)

    val tileAssignments: Flow<List<TileAssignment>> = context.settingsDataStore.data.map { preferences ->
        preferences[Keys.TILE_ASSIGNMENTS]
            ?.let { encoded -> runCatching { json.decodeFromString<List<TileAssignment>>(encoded) }.getOrNull() }
            .orEmpty()
            .filter { it.slot in 1..MAX_TILE_SLOTS }
            .distinctBy { it.slot }
            .sortedBy { it.slot }
    }

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
    val lastHealthSync: Flow<Long?> = context.settingsDataStore.data.map { it[Keys.LAST_HEALTH_SYNC] }

    suspend fun currentConfig(): HubConfig = config.first()

    suspend fun saveConfig(config: HubConfig, passwordUpdate: HubPasswordUpdate) {
        val normalized = config.copy(
            localUrl = normalizeSprutEndpoint(config.localUrl, secureByDefault = false),
            cloudUrl = normalizeSprutEndpoint(config.cloudUrl, secureByDefault = true),
            serial = normalizeHubSerial(config.serial),
        )
        validate(normalized)
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.MODE] = normalized.mode.name
            preferences[Keys.LOCAL_URL] = normalized.localUrl
            preferences[Keys.CLOUD_URL] = normalized.cloudUrl
            preferences[Keys.SERIAL] = normalized.serial.trim()
            preferences[Keys.EMAIL] = normalized.email.trim()
        }
        secretStore.updatePasswords(passwordUpdate)
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

    suspend fun saveHealthMetrics(metrics: Set<HealthMetric>) {
        require(metrics.isNotEmpty()) { "Выберите хотя бы один показатель здоровья" }
        context.settingsDataStore.edit { it[Keys.HEALTH_METRICS] = metrics.joinToString(",", transform = HealthMetric::name) }
    }

    suspend fun saveHealthBinding(binding: HealthDeviceBinding) {
        context.settingsDataStore.edit { preferences ->
            preferences[Keys.HEALTH_BINDING] = json.encodeToString(binding)
        }
    }

    suspend fun setHealthEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.HEALTH_ENABLED] = enabled }
    }

    suspend fun markHealthSynced(epochMs: Long = System.currentTimeMillis()) {
        context.settingsDataStore.edit { it[Keys.LAST_HEALTH_SYNC] = epochMs }
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

    private fun validate(config: HubConfig) {
        require(config.serial.isNotBlank()) { "Укажите серийный номер SprutHub" }
        when (config.mode) {
            ConnectionMode.AUTO -> require(config.localUrl.isNotBlank() || config.cloudUrl.isNotBlank()) {
                "Укажите хотя бы один адрес SprutHub"
            }
            ConnectionMode.LOCAL -> require(config.localUrl.isNotBlank()) { "Укажите локальный адрес SprutHub" }
            ConnectionMode.CLOUD -> require(config.cloudUrl.isNotBlank()) { "Укажите облачный адрес SprutHub" }
        }
        listOf(config.localUrl to "локальный", config.cloudUrl to "облачный")
            .filter { (url) -> url.isNotBlank() }
            .forEach { (url, label) ->
            val uri = runCatching { URI(url.trim()) }.getOrNull()
                ?: error("Некорректный $label адрес")
            require(uri.scheme == "ws" || uri.scheme == "wss") { "$label адрес должен начинаться с ws:// или wss://" }
            require(uri.host != null) { "В $label адресе не найден хост" }
            if (uri.scheme == "ws") {
                require(label == "локальный" && isPrivateLanHost(uri.host)) {
                    "Незашифрованный ws:// разрешён только для локального адреса; используйте wss://"
                }
            }
        }
    }

    private object Keys {
        val MODE = stringPreferencesKey("connection_mode")
        val LOCAL_URL = stringPreferencesKey("local_url")
        val CLOUD_URL = stringPreferencesKey("cloud_url")
        val SERIAL = stringPreferencesKey("serial")
        val EMAIL = stringPreferencesKey("email")
        val TILE_ASSIGNMENTS = stringPreferencesKey("tile_assignments")
        val HEALTH_METRICS = stringPreferencesKey("health_metrics")
        val HEALTH_BINDING = stringPreferencesKey("health_binding")
        val HEALTH_ENABLED = booleanPreferencesKey("health_enabled")
        val LAST_HEALTH_SYNC = longPreferencesKey("last_health_sync")
    }

    companion object {
        const val MAX_TILE_SLOTS = 12
        val DEFAULT_HEALTH_METRICS = setOf(
            HealthMetric.STEPS,
            HealthMetric.HEART_RATE,
            HealthMetric.SLEEP,
            HealthMetric.WEIGHT,
            HealthMetric.OXYGEN_SATURATION,
            HealthMetric.ACTIVE_CALORIES,
        )
    }
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
