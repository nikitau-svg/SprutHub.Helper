package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HubConfig
import java.io.IOException
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
        val key = listOf(config.mode, config.localUrl, config.cloudUrl, config.serial, config.email, config.password.hashCode())
            .joinToString("|")
        session?.takeIf { !force && sessionKey == key && it.isOpen }?.let {
            return ConnectedEndpoint(it.endpoint, it.endpoint == config.localUrl)
        }

        session?.close()
        session = null
        val failures = mutableListOf<String>()
        for (endpoint in endpoints(config)) {
            val candidate = SocketSession(endpoint, config.serial)
            val attempt = runCatching {
                candidate.open()
                authenticate(candidate, config)
                candidate
            }
            val connected = attempt.getOrNull()
            if (connected != null) {
                session = connected
                sessionKey = key
                return ConnectedEndpoint(endpoint, endpoint == config.localUrl)
            }
            candidate.close()
            failures += "${safeEndpoint(endpoint)}: ${attempt.exceptionOrNull()?.message ?: "ошибка"}"
        }
        throw SprutConnectionException(failures.joinToString("; "))
    }

    suspend fun call(config: HubConfig, params: JsonObject): JsonElement {
        connect(config)
        val active = session ?: throw SprutConnectionException("Соединение не создано")
        return try {
            active.request(params)
        } catch (first: Exception) {
            connect(config, force = true)
            (session ?: throw first).request(params)
        }
    }

    suspend fun disconnect() = connectionMutex.withLock {
        session?.close()
        session = null
        sessionKey = ""
    }

    private fun endpoints(config: HubConfig): List<String> = when (config.mode) {
        ConnectionMode.AUTO -> listOf(config.localUrl, config.cloudUrl)
        ConnectionMode.LOCAL -> listOf(config.localUrl)
        ConnectionMode.CLOUD -> listOf(config.cloudUrl)
    }.map(String::trim).filter(String::isNotBlank).distinct()

    private suspend fun authenticate(socket: SocketSession, config: HubConfig) {
        var response = socket.request(accountRequest("auth"))
        repeat(4) {
            findStringByKey(response, "token")?.takeIf(String::isNotBlank)?.let {
                socket.token = it
                return
            }
            val marker = response.toString().uppercase()
            response = when {
                marker.contains("QUESTION_TYPE_EMAIL") || marker.contains("\"EMAIL\"") -> {
                    require(config.email.isNotBlank()) { "SprutHub запросил e-mail" }
                    socket.request(answerRequest(config.email))
                }
                marker.contains("QUESTION_TYPE_PASSWORD") || marker.contains("\"PASSWORD\"") -> {
                    require(config.password.isNotBlank()) { "SprutHub запросил пароль" }
                    socket.request(answerRequest(config.password))
                }
                else -> return
            }
        }

        findStringByKey(response, "token")?.takeIf(String::isNotBlank)?.let {
            socket.token = it
            return
        }

        if (config.email.isNotBlank()) {
            var legacy = socket.request(
                buildJsonObject {
                    put("account", buildJsonObject {
                        put("login", buildJsonObject { put("login", config.email) })
                    })
                },
            )
            if (legacy.toString().uppercase().contains("PASSWORD") && config.password.isNotBlank()) {
                legacy = socket.request(answerRequest(config.password))
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

    inner class SocketSession(
        val endpoint: String,
        private val serial: String,
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
