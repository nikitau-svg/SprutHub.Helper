package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.HubConfig
import io.github.nikitau.spruthubhelper.data.SprutCatalog
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class LoadedSprutCatalog(
    val endpoint: ConnectedEndpoint,
    val catalog: SprutCatalog,
)

/** A testable boundary for one complete, authoritative catalog snapshot. */
class SprutCatalogLoader(
    private val client: SprutRpcClient,
    private val parser: SprutCatalogParser,
) {
    private val versionRoutes = ConcurrentHashMap<String, VersionRoute>()
    private val unsupportedScenarioEndpoints = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(config: HubConfig, forceConnection: Boolean = false): LoadedSprutCatalog {
        val endpoint = client.connect(config, force = forceConnection)
        val versionResponse = optionalVersion(config, endpoint.url)
        val rooms = client.call(config, request("room", "list"))
        val accessories = client.call(
            config,
            request(
                section = "accessory",
                operation = "list",
                operationBody = buildJsonObject { put("expand", "services+characteristics") },
            ),
        )
        val scenarios = optionalScenarios(config, endpoint.url)
        return LoadedSprutCatalog(
            endpoint = endpoint,
            catalog = parser.parse(
                roomsResponse = rooms,
                accessoriesResponse = accessories,
                scenariosResponse = scenarios,
                hubVersion = findVersion(versionResponse),
            ),
        )
    }

    private suspend fun optionalVersion(config: HubConfig, endpoint: String): JsonElement {
        suspend fun callRoute(route: VersionRoute): JsonElement = when (route) {
            VersionRoute.SERVER_VERSION -> client.call(config, request("server", "version"))
            VersionRoute.HUB_LIST -> client.call(config, request("hub", "list"))
            VersionRoute.NONE -> JsonNull
        }

        versionRoutes[endpoint]?.let { cached ->
            return try {
                callRoute(cached)
            } catch (_: SprutProtocolException) {
                versionRoutes.remove(endpoint, cached)
                discoverVersionRoute(config, endpoint)
            }
        }
        return discoverVersionRoute(config, endpoint)
    }

    private suspend fun discoverVersionRoute(config: HubConfig, endpoint: String): JsonElement = try {
        client.call(config, request("server", "version")).also {
            versionRoutes[endpoint] = VersionRoute.SERVER_VERSION
        }
    } catch (_: SprutProtocolException) {
        try {
            client.call(config, request("hub", "list")).also {
                versionRoutes[endpoint] = VersionRoute.HUB_LIST
            }
        } catch (_: SprutProtocolException) {
            versionRoutes[endpoint] = VersionRoute.NONE
            JsonNull
        }
    }

    private suspend fun optionalScenarios(config: HubConfig, endpoint: String): JsonElement {
        if (endpoint in unsupportedScenarioEndpoints) return JsonNull
        return try {
            client.call(config, request("scenario", "list"))
        } catch (_: SprutProtocolException) {
            unsupportedScenarioEndpoints += endpoint
            JsonNull
        }
    }

    private fun request(
        section: String,
        operation: String,
        operationBody: JsonObject = buildJsonObject {},
    ): JsonObject = buildJsonObject {
        put(section, buildJsonObject { put(operation, operationBody) })
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

    private enum class VersionRoute {
        SERVER_VERSION,
        HUB_LIST,
        NONE,
    }
}
