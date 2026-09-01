package io.github.nikitau.spruthubhelper.sprut

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Small in-process SprutHub protocol emulator for transport and recovery tests.
 * It intentionally implements only JSON-RPC framing and the authentication
 * shortcut required by [SprutRpcClient]. Test cases control delays, protocol
 * errors, disconnects and operation results explicitly.
 */
internal class FakeSprutHubServer : Closeable {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val results = ConcurrentHashMap<String, JsonElement>()
    private val delaysMs = ConcurrentHashMap<String, Long>()
    private val unsupported = ConcurrentHashMap.newKeySet<String>()
    private val ignored = ConcurrentHashMap.newKeySet<String>()
    private val disconnectNextOperation = AtomicReference<String?>(null)
    private val authenticationStatus = AtomicReference<String?>(null)

    val connectionCount = AtomicInteger()
    val operations = CopyOnWriteArrayList<String>()

    val webSocketUrl: String
        get() = server.url("/spruthub").toString().replaceFirst("http", "ws")

    fun start() {
        repeat(MAX_CONNECTIONS) { server.enqueue(webSocketUpgrade()) }
        server.start()
    }

    fun respond(operation: String, result: JsonElement) {
        results[operation] = result
    }

    fun markUnsupported(operation: String) {
        unsupported += operation
    }

    fun delay(operation: String, delayMs: Long) {
        delaysMs[operation] = delayMs
    }

    fun ignore(operation: String) {
        ignored += operation
    }

    fun disconnectNext(operation: String) {
        disconnectNextOperation.set(operation)
    }

    fun rejectAuthentication(status: String = "ACCOUNT_RESPONSE_FAILED") {
        authenticationStatus.set(status)
    }

    fun disconnectClients() {
        sockets.forEach { it.close(TEST_CLOSE_CODE, "fixture disconnect") }
    }

    fun sendEvent(event: JsonElement) {
        sockets.forEach { it.send(event.toString()) }
    }

    override fun close() {
        disconnectClients()
        server.shutdown()
    }

    private fun webSocketUpgrade(): MockResponse = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets += webSocket
                connectionCount.incrementAndGet()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = json.parseToJsonElement(text) as? JsonObject ?: return
                val id = request["id"] ?: return
                val params = request["params"] as? JsonObject ?: return
                val operation = operationName(params)
                operations += operation

                val disconnectOperation = disconnectNextOperation.get()
                if (
                    disconnectOperation == operation &&
                    disconnectNextOperation.compareAndSet(disconnectOperation, null)
                ) {
                    webSocket.close(TEST_CLOSE_CODE, "fixture disconnect")
                    return
                }
                if (operation in ignored) return

                delaysMs[operation]?.takeIf { it > 0L }?.let(Thread::sleep)
                when {
                    operation == "account.auth" -> sendResult(webSocket, id, authenticationResponse())
                    operation in unsupported -> sendError(webSocket, id, "Action not found: '$operation'")
                    else -> sendResult(
                        webSocket,
                        id,
                        results[operation] ?: buildJsonObject { put("ok", true) },
                    )
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets -= webSocket
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                sockets -= webSocket
            }
        },
    )

    private fun sendResult(webSocket: WebSocket, id: JsonElement, result: JsonElement) {
        webSocket.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString(),
        )
    }

    private fun authenticationResponse(): JsonElement = authenticationStatus.get()?.let { status ->
        buildJsonObject { put("status", status) }
    } ?: buildJsonObject { put("token", "fake-session-token") }

    private fun sendError(webSocket: WebSocket, id: JsonElement, message: String) {
        webSocket.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", buildJsonObject {
                    put("code", -32601)
                    put("message", message)
                })
            }.toString(),
        )
    }

    private fun operationName(params: JsonObject): String {
        val section = params.keys.firstOrNull().orEmpty()
        val operation = (params[section] as? JsonObject)?.keys?.firstOrNull().orEmpty()
        return listOf(section, operation).filter(String::isNotBlank).joinToString(".")
    }

    private companion object {
        const val MAX_CONNECTIONS = 8
        const val TEST_CLOSE_CODE = 1011
    }
}
