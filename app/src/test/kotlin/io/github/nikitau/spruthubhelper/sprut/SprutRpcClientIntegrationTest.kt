package io.github.nikitau.spruthubhelper.sprut

import java.io.IOException
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.HubConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SprutRpcClientIntegrationTest {
    private lateinit var server: FakeSprutHubServer
    private lateinit var client: SprutRpcClient
    private lateinit var config: HubConfig

    @Before
    fun setUp() {
        server = FakeSprutHubServer().also(FakeSprutHubServer::start)
        client = SprutRpcClient(
            socketOpenTimeoutMs = 500,
            requestTimeoutMs = 300,
        )
        config = HubConfig(
            mode = ConnectionMode.LOCAL,
            localUrl = server.webSocketUrl,
            email = "fixture@example.invalid",
            localPassword = "fixture-password",
        )
    }

    @After
    fun tearDown() = runBlocking {
        client.disconnect()
        server.close()
    }

    @Test
    fun unsupportedOptionalOperationFallsBackOnSameAuthenticatedSocket() = runBlocking {
        server.markUnsupported("server.version")
        server.respond("hub.list", buildJsonObject { put("version", "2.0-fixture") })

        val unsupported = runCatching {
            client.call(config, request("server", "version"), reconnectOnFailure = false)
        }
        val fallback = client.call(config, request("hub", "list"))

        assertTrue(unsupported.exceptionOrNull() is SprutProtocolException)
        assertEquals("2.0-fixture", fallback.jsonObject.getValue("version").jsonPrimitive.content)
        assertEquals(1, server.connectionCount.get())
    }

    @Test
    fun protocolErrorDoesNotReconnectEvenWithDefaultCallPolicy() = runBlocking {
        server.markUnsupported("room.list")

        val result = runCatching { client.call(config, request("room", "list")) }

        assertTrue(result.exceptionOrNull() is SprutProtocolException)
        assertEquals(1, server.connectionCount.get())
        assertEquals(1, server.operations.count { it == "account.auth" })
    }

    @Test
    fun transportDropRetriesOnceOnANewAuthenticatedSocket() = runBlocking {
        server.disconnectNext("room.list")
        server.respond("room.list", buildJsonObject { put("rooms", 3) })

        val response = client.call(config, request("room", "list"))

        assertEquals(3, response.jsonObject.getValue("rooms").jsonPrimitive.content.toInt())
        assertEquals(2, server.connectionCount.get())
        assertEquals(2, server.operations.count { it == "account.auth" })
    }

    @Test
    fun unexpectedSocketLossIsExposedWithoutWaitingForAnotherRpc() = runBlocking {
        client.connect(config)

        server.disconnectClients()
        val status = withTimeout(2_000) {
            client.transportStatus.first { it.phase == SprutTransportPhase.ERROR }
        }

        assertEquals(SprutTransportPhase.ERROR, status.phase)
        assertTrue(status.message.isNotBlank())
    }

    @Test
    fun cancellingDelayedRequestDoesNotCreateAReplacementConnection() = runBlocking {
        server.delay("accessory.list", 500)
        client.connect(config)

        val request = async(Dispatchers.IO) {
            client.call(config, request("accessory", "list"))
        }
        delay(50)
        request.cancelAndJoin()
        delay(100)

        assertEquals(1, server.connectionCount.get())
        assertEquals(SprutTransportPhase.CONNECTED_LOCAL, client.transportStatus.value.phase)
    }

    @Test
    fun callerTimeoutRemainsCancellationAndDoesNotTriggerReconnect() = runBlocking {
        server.delay("accessory.list", 500)
        client.connect(config)

        val result = runCatching {
            withTimeout(50) { client.call(config, request("accessory", "list")) }
        }

        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(1, server.connectionCount.get())
        assertEquals(SprutTransportPhase.CONNECTED_LOCAL, client.transportStatus.value.phase)
    }

    @Test
    fun delayedResponseStillCompletesOnTheOriginalConnection() = runBlocking {
        server.delay("scenario.list", 120)
        server.respond("scenario.list", buildJsonObject { put("count", 7) })

        val response = client.call(config, request("scenario", "list"))

        assertEquals(7, response.jsonObject.getValue("count").jsonPrimitive.content.toInt())
        assertEquals(1, server.connectionCount.get())
    }

    @Test
    fun unansweredRequestRetriesOnceThenMarksTransportOffline() = runBlocking {
        server.ignore("accessory.list")

        val result = runCatching { client.call(config, request("accessory", "list")) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, server.connectionCount.get())
        assertEquals(SprutTransportPhase.ERROR, client.transportStatus.value.phase)
    }

    @Test
    fun autoModeFallsBackFromDeadLocalAddressToCloudSocket() = runBlocking {
        val autoConfig = config.copy(
            mode = ConnectionMode.AUTO,
            localUrl = "ws://127.0.0.1:1/spruthub",
            cloudUrl = server.webSocketUrl,
            cloudPassword = "fixture-cloud-password",
        )

        val endpoint = client.connect(autoConfig)

        assertFalse(endpoint.isLocal)
        assertEquals(server.webSocketUrl, endpoint.url)
        assertEquals(SprutTransportPhase.CONNECTED_CLOUD, client.transportStatus.value.phase)
    }

    @Test
    fun autoModeFallsBackFromRejectedLocalAuthenticationToCloudSocket() = runBlocking {
        val local = FakeSprutHubServer().also(FakeSprutHubServer::start)
        try {
            local.rejectAuthentication()
            val autoConfig = config.copy(
                mode = ConnectionMode.AUTO,
                localUrl = local.webSocketUrl,
                cloudUrl = server.webSocketUrl,
                localPassword = "rejected-local-password",
                cloudPassword = "accepted-cloud-password",
            )

            val endpoint = client.connect(autoConfig)

            assertFalse(endpoint.isLocal)
            assertEquals(server.webSocketUrl, endpoint.url)
            assertEquals(1, local.operations.count { it == "account.auth" })
            assertEquals(1, server.operations.count { it == "account.auth" })
            assertEquals(SprutTransportPhase.CONNECTED_CLOUD, client.transportStatus.value.phase)
        } finally {
            local.close()
        }
    }

    @Test
    fun droppedLocalTransportRecoversThroughCloudSocket() = runBlocking {
        val local = FakeSprutHubServer().also(FakeSprutHubServer::start)
        try {
            val autoConfig = config.copy(
                mode = ConnectionMode.AUTO,
                localUrl = local.webSocketUrl,
                cloudUrl = server.webSocketUrl,
                localPassword = "local-password",
                cloudPassword = "cloud-password",
            )
            server.respond("room.list", buildJsonObject { put("channel", "cloud") })
            assertTrue(client.connect(autoConfig).isLocal)

            local.close()
            withTimeout(1_000) {
                client.transportStatus.first { it.phase == SprutTransportPhase.ERROR }
            }
            val response = client.call(autoConfig, request("room", "list"))

            assertEquals("cloud", response.jsonObject.getValue("channel").jsonPrimitive.content)
            assertEquals(SprutTransportPhase.CONNECTED_CLOUD, client.transportStatus.value.phase)
            assertTrue(server.operations.contains("room.list"))
        } finally {
            local.close()
        }
    }

    @Test
    fun failureOfBothAutoEndpointsDoesNotLeakEitherPassword() = runBlocking {
        val local = FakeSprutHubServer().also(FakeSprutHubServer::start)
        try {
            local.rejectAuthentication()
            server.rejectAuthentication()
            val localPassword = "very-private-local-password"
            val cloudPassword = "very-private-cloud-password"
            val autoConfig = config.copy(
                mode = ConnectionMode.AUTO,
                localUrl = local.webSocketUrl,
                cloudUrl = server.webSocketUrl,
                localPassword = localPassword,
                cloudPassword = cloudPassword,
            )

            val result = runCatching { client.connect(autoConfig) }
            val message = result.exceptionOrNull()?.message.orEmpty()

            assertTrue(result.exceptionOrNull() is SprutConnectionException)
            assertEquals(SprutTransportPhase.ERROR, client.transportStatus.value.phase)
            assertFalse(message.contains(localPassword))
            assertFalse(message.contains(cloudPassword))
            assertTrue(message.contains("локальные данные входа"))
            assertTrue(message.contains("облачные данные входа"))
        } finally {
            local.close()
        }
    }

    @Test
    fun parserKeepsValidAccessoryWhenResponseContainsUnknownWrapperAndBrokenEntries() = runBlocking {
        val partialCatalog = Json.parseToJsonElement(
            """
            {"futurePayload":{"accessories":[
              {"id":1,"name":"Неполное устройство"},
              "unexpected-entry",
              {"id":7,"name":"Торшер","services":[
                {"id":11,"type":"S_LIGHTBULB","characteristics":[
                  {"id":1,"type":"C_ON","control":{"write":true,"value":{"boolValue":true}}}
                ]}
              ]}
            ]}}
            """.trimIndent(),
        )
        server.respond("accessory.list", partialCatalog)

        val response = client.call(config, request("accessory", "list"))
        val catalog = SprutCatalogParser().parse(JsonNull, response)

        assertEquals(1, catalog.controls.size)
        assertEquals("7:11:1", catalog.controls.single().id)
        assertEquals(ControlBehavior.TOGGLE, catalog.controls.single().behavior)
    }

    @Test
    fun rejectedAuthenticationEndsInOfflineStateWithoutLeakingPassword() = runBlocking {
        server.rejectAuthentication()

        val result = runCatching { client.connect(config) }
        val message = result.exceptionOrNull()?.message.orEmpty()

        assertTrue(result.exceptionOrNull() is SprutConnectionException)
        assertEquals(SprutTransportPhase.ERROR, client.transportStatus.value.phase)
        assertFalse(message.contains("fixture-password"))
        assertTrue(message.contains("локальные данные входа"))
    }

    @Test
    fun unsolicitedCharacteristicEventReachesSubscriberOnOpenSocket() = runBlocking {
        client.connect(config)
        val incoming = async {
            withTimeout(1_000) { client.events.first() }
        }
        delay(20)
        server.sendEvent(
            Json.parseToJsonElement(
                """{"event":{"characteristic":{"characteristics":[
                  {"aId":7,"sId":11,"cId":1,"control":{"value":{"boolValue":true}}}
                ]}}}""",
            ),
        )

        val updates = SprutCatalogParser().parseUpdates(incoming.await())

        assertEquals(1, updates.size)
        assertEquals(true, updates.single().value.boolValue)
    }

    private fun request(section: String, operation: String): JsonObject = buildJsonObject {
        put(section, buildJsonObject { put(operation, buildJsonObject {}) })
    }
}
