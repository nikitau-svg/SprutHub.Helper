package io.github.nikitau.spruthubhelper.sprut

import android.util.Log
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HubConfig
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

internal class SprutEndpointCandidate(
    val url: String,
    val isLocal: Boolean,
    val password: String,
) {
    override fun toString(): String =
        "SprutEndpointCandidate(url=$url, isLocal=$isLocal, password=<redacted>)"
}

internal fun connectionCandidates(config: HubConfig): List<SprutEndpointCandidate> {
    fun candidate(url: String, isLocal: Boolean) = SprutEndpointCandidate(
        url = url.trim(),
        isLocal = isLocal,
        password = config.passwordFor(isLocal),
    )

    return when (config.mode) {
        ConnectionMode.AUTO -> listOf(candidate(config.localUrl, true), candidate(config.cloudUrl, false))
        ConnectionMode.LOCAL -> listOf(candidate(config.localUrl, true))
        ConnectionMode.CLOUD -> listOf(candidate(config.cloudUrl, false))
    }.filter { it.url.isNotBlank() }
}

class SprutRpcClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val connectionMutex = Mutex()
    private val _events = MutableSharedFlow<JsonElement>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var session: SocketSession? = null
    private var sessionKey: String = ""

    val events: SharedFlow<JsonElement> = _events

    suspend fun connect(config: HubConfig, force: Boolean = false): ConnectedEndpoint = connectionMutex.withLock {
        val candidates = connectionCandidates(config)
        val key = sessionIdentity(config, candidates)
        session?.takeIf { !force && sessionKey == key && it.isOpen }?.let {
            return ConnectedEndpoint(it.endpoint, it.isLocal)
        }

        session?.close()
        session = null
        val failures = mutableListOf<String>()
        for (endpoint in candidates) {
            val candidate = SocketSession(endpoint.url, config.serial, endpoint.isLocal)
            val attempt = runCatching {
                candidate.open()
                authenticate(
                    socket = candidate,
                    email = config.email,
                    password = endpoint.password,
                    isLocal = endpoint.isLocal,
                )
                candidate
            }
            val connected = attempt.getOrNull()
            if (connected != null) {
                session = connected
                sessionKey = key
                return ConnectedEndpoint(endpoint.url, endpoint.isLocal)
            }
            candidate.close()
            failures += "${safeEndpoint(endpoint.url)}: ${safeFailureMessage(attempt.exceptionOrNull(), endpoint.password)}"
        }
        throw SprutConnectionException(failures.joinToString("; "))
    }

    suspend fun call(config: HubConfig, params: JsonObject): JsonElement {
        val operation = operationName(params)
        connect(config)
        val active = session ?: throw SprutConnectionException("Соединение не создано")
        return try {
            active.request(params).also { Log.d(LOG_TAG, "RPC $operation succeeded") }
        } catch (first: Exception) {
            Log.w(LOG_TAG, "RPC $operation failed, reconnecting: ${first.message}")
            connect(config, force = true)
            (session ?: throw first).request(params).also { Log.d(LOG_TAG, "RPC $operation succeeded after reconnect") }
        }
    }

    suspend fun disconnect() = connectionMutex.withLock {
        session?.close()
        session = null
        sessionKey = ""
    }

    private suspend fun authenticate(
        socket: SocketSession,
        email: String,
        password: String,
        isLocal: Boolean,
    ) {
        var response = socket.request(accountRequest("auth"))
        repeat(4) {
            findStringByKey(response, "token")?.takeIf(String::isNotBlank)?.let {
                socket.token = it
                return
            }
            val marker = response.toString().uppercase()
            response = when {
                marker.contains("QUESTION_TYPE_EMAIL") || marker.contains("\"EMAIL\"") -> {
                    require(email.isNotBlank()) { "SprutHub запросил e-mail" }
                    socket.request(answerRequest(email))
                }
                marker.contains("QUESTION_TYPE_PASSWORD") || marker.contains("\"PASSWORD\"") -> {
                    val endpointLabel = if (isLocal) "локальный" else "облачный"
                    require(password.isNotBlank()) { "SprutHub запросил $endpointLabel пароль" }
                    socket.request(answerRequest(password))
                }
                else -> return
            }
        }

        findStringByKey(response, "token")?.takeIf(String::isNotBlank)?.let {
            socket.token = it
            return
        }

        if (email.isNotBlank()) {
            var legacy = socket.request(
                buildJsonObject {
                    put("account", buildJsonObject {
                        put("login", buildJsonObject { put("login", email) })
                    })
                },
            )
            if (legacy.toString().uppercase().contains("PASSWORD") && password.isNotBlank()) {
                legacy = socket.request(answerRequest(password))
            }
            findStringByKey(legacy, "token")?.takeIf(String::isNotBlank)?.let {
                socket.token = it
            }
        }
    }

    private fun accountRequest(operation: String): JsonObject = buildJsonObject {
        put("account", buildJsonObject { put(operation, buildJsonObject {}) })
    }

    private fun answerRequest(value: String): JsonObject = buildJsonObject {
        put("account", buildJsonObject {
            put("answer", buildJsonObject { put("data", value) })
        })
    }

    private fun findStringByKey(element: JsonElement, wanted: String): String? = when (element) {
        is JsonObject -> {
            element.entries.firstOrNull { it.key.equals(wanted, ignoreCase = true) }
                ?.value
                ?.let { value -> runCatching { value.jsonPrimitive.content }.getOrNull() }
                ?: element.values.firstNotNullOfOrNull { findStringByKey(it, wanted) }
        }
        is kotlinx.serialization.json.JsonArray -> element.firstNotNullOfOrNull { findStringByKey(it, wanted) }
        else -> null
    }

    private fun safeEndpoint(endpoint: String): String = runCatching {
        val uri = java.net.URI(endpoint)
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}${uri.path.orEmpty()}"
    }.getOrDefault("адрес SprutHub")

    private fun operationName(params: JsonObject): String {
        val section = params.keys.firstOrNull().orEmpty()
        val operation = (params[section] as? JsonObject)?.keys?.firstOrNull().orEmpty()
        return listOf(section, operation).filter(String::isNotBlank).joinToString(".").ifBlank { "unknown" }
    }

    private fun safeFailureMessage(error: Throwable?, password: String): String {
        val message = error?.message ?: return "ошибка"
        return if (password.isEmpty()) message else message.replace(password, "<redacted>")
    }

    private fun sessionIdentity(
        config: HubConfig,
        candidates: List<SprutEndpointCandidate>,
    ): String = buildString {
        append(config.mode).append('|')
        append(config.serial).append('|')
        append(config.email).append('|')
        candidates.forEach { candidate ->
            append(if (candidate.isLocal) "local" else "cloud").append(':')
            append(candidate.url).append(':')
            append(secretFingerprint(candidate.password)).append('|')
        }
    }

    private fun secretFingerprint(secret: String): String = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    inner class SocketSession(
        val endpoint: String,
        private val serial: String,
        val isLocal: Boolean,
    ) : WebSocketListener() {
        private val opened = CompletableDeferred<Unit>()
        private val requestIds = AtomicLong(1)
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
        private lateinit var socket: WebSocket

        @Volatile
        var token: String = ""

        @Volatile
        var isOpen: Boolean = false
            private set

        suspend fun open() {
            val request = Request.Builder().url(endpoint).build()
            socket = httpClient.newWebSocket(request, this)
            withTimeout(8_000) { opened.await() }
        }

        suspend fun request(params: JsonObject): JsonElement {
            check(isOpen) { "WebSocket закрыт" }
            val id = requestIds.getAndIncrement()
            val deferred = CompletableDeferred<JsonElement>()
            pending[id] = deferred
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("params", params)
                put("id", id)
                if (token.isNotBlank()) put("token", token)
                if (serial.isNotBlank()) put("serial", serial)
            }
            if (!socket.send(payload.toString())) {
                pending.remove(id)
                throw IOException("SprutHub не принял запрос")
            }
            val response = try {
                withTimeout(12_000) { deferred.await() }
            } finally {
                pending.remove(id)
            }
            val error = (response as? JsonObject)?.get("error")
            if (error != null && error !is JsonNull) {
                throw SprutProtocolException(error.toString().take(300))
            }
            return (response as? JsonObject)?.get("result") ?: response
        }

        fun close() {
            isOpen = false
            if (::socket.isInitialized) socket.close(1000, "client closing")
            failPending(IOException("WebSocket закрыт"))
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return
            val id = (message as? JsonObject)?.get("id")
                ?.let { runCatching { it.jsonPrimitive.content.toLong() }.getOrNull() }
            if (id != null && pending.remove(id)?.complete(message) == true) return
            _events.tryEmit(message)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            failPending(IOException("SprutHub закрыл соединение ($code)"))
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            isOpen = false
            opened.completeExceptionally(throwable)
            failPending(IOException(throwable.message ?: "Ошибка WebSocket", throwable))
        }

        private fun failPending(error: Throwable) {
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
    }
}

data class ConnectedEndpoint(
    val url: String,
    val isLocal: Boolean,
)

class SprutConnectionException(message: String) : IOException(message)
class SprutProtocolException(message: String) : IOException(message)

private const val LOG_TAG = "SprutHubRpc"
