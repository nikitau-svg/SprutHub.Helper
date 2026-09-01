package io.github.nikitau.spruthubhelper.sprut

import java.io.IOException
import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.ControlBehavior
import io.github.nikitau.spruthubhelper.data.HubConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SprutCatalogLoaderIntegrationTest {
    private lateinit var server: FakeSprutHubServer
    private lateinit var client: SprutRpcClient
    private lateinit var loader: SprutCatalogLoader
    private lateinit var config: HubConfig

    @Before
    fun setUp() {
        server = FakeSprutHubServer().also(FakeSprutHubServer::start)
        client = SprutRpcClient(
            socketOpenTimeoutMs = 500,
            requestTimeoutMs = 500,
        )
        loader = SprutCatalogLoader(client, SprutCatalogParser())
        config = HubConfig(
            mode = ConnectionMode.LOCAL,
            localUrl = server.webSocketUrl,
            localPassword = "fixture-password",
        )
    }

    @After
    fun tearDown() = runBlocking {
        client.disconnect()
        server.close()
    }

    @Test
    fun optionalLegacyOperationsDoNotCauseRepeatedAuthentication() = runBlocking {
        server.markUnsupported("server.version")
        server.markUnsupported("scenario.list")
        server.respond("hub.list", Json.parseToJsonElement("""{"version":"2.0-fixture"}"""))
        server.respond("room.list", rooms())
        server.respond("accessory.list", accessories())

        val loaded = loader.load(config)
        val repeated = loader.load(config)

        assertEquals("2.0-fixture", loaded.catalog.hubVersion)
        assertEquals("2.0-fixture", repeated.catalog.hubVersion)
        assertEquals(1, loaded.catalog.controls.size)
        assertEquals(ControlBehavior.TOGGLE, loaded.catalog.controls.single().behavior)
        assertEquals(1, server.connectionCount.get())
        assertEquals(1, server.operations.count { it == "account.auth" })
        assertEquals(1, server.operations.count { it == "server.version" })
        assertEquals(2, server.operations.count { it == "hub.list" })
        assertEquals(1, server.operations.count { it == "scenario.list" })
    }

    @Test
    fun delayedRequiredResponseStillProducesOneCoherentSnapshot() = runBlocking {
        server.respond("server.version", Json.parseToJsonElement("""{"version":"2.1"}"""))
        server.respond("room.list", rooms())
        server.respond("accessory.list", accessories())
        server.respond("scenario.list", Json.parseToJsonElement("""{"scenarios":[]}"""))
        server.delay("accessory.list", 150)

        val loaded = loader.load(config)

        assertEquals("Дом", loaded.catalog.controls.single().room)
        assertEquals(1, server.connectionCount.get())
    }

    @Test
    fun partialCatalogIsReturnedAsEmptyInsteadOfInventingControls() = runBlocking {
        server.respond("server.version", Json.parseToJsonElement("""{"version":"future"}"""))
        server.respond("room.list", Json.parseToJsonElement("""{"futureRooms":null}"""))
        server.respond(
            "accessory.list",
            Json.parseToJsonElement("""{"accessories":[{"id":7,"name":"Без сервисов"}]}"""),
        )
        server.respond("scenario.list", Json.parseToJsonElement("""{"scenarios":[]}"""))

        val loaded = loader.load(config)

        assertTrue(loaded.catalog.controls.isEmpty())
        assertEquals("future", loaded.catalog.hubVersion)
    }

    @Test
    fun scenarioTransportFailureRejectsIncompleteSnapshot() = runBlocking {
        server.respond("server.version", Json.parseToJsonElement("""{"version":"2.1"}"""))
        server.respond("room.list", rooms())
        server.respond("accessory.list", accessories())
        server.ignore("scenario.list")

        val result = runCatching { loader.load(config) }

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(2, server.connectionCount.get())
        assertEquals(SprutTransportPhase.ERROR, client.transportStatus.value.phase)
    }

    private fun rooms() = Json.parseToJsonElement(
        """{"rooms":[{"id":1,"name":"Дом"}]}""",
    )

    private fun accessories() = Json.parseToJsonElement(
        """
        {"accessories":[{"id":7,"name":"Торшер","roomId":1,"services":[
          {"id":11,"type":"S_LIGHTBULB","characteristics":[
            {"id":1,"type":"C_ON","control":{"write":true,"value":{"boolValue":false}}}
          ]}
        ]}]}
        """.trimIndent(),
    )
}
