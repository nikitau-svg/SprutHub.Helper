package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.HealthDeviceBinding
import io.github.nikitau.spruthubhelper.data.HealthTarget
import io.github.nikitau.spruthubhelper.data.PhoneSensor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SprutHeartbeatScenarioManagerTest {
    private val binding = HealthDeviceBinding(
        accessoryId = "41",
        name = "Телефон · Test",
        roomId = "2",
        targets = listOf(
            HealthTarget(
                key = PhoneSensor.SYNC_HEARTBEAT.name,
                serviceId = "7",
                characteristicId = "9",
                valueField = "intValue",
                serviceType = "C_Option",
                characteristicType = "C_GenericInteger",
            ),
        ),
    )
    private val target = binding.targets.single()

    @Test
    fun `scenario payload is a resettable 45 minute dead man timer`() {
        val body = scenarioBody(binding, target)
        val data = Json.parseToJsonElement(body.getValue("data").jsonPrimitive.content).jsonObject
        val fork = data.getValue("targets").jsonArray.single().jsonObject
        val condition = fork.getValue("if").jsonObject
            .getValue("conditions").jsonArray.single().jsonObject
        val delay = fork.getValue("then").jsonArray.single().jsonObject
        val notifications = delay.getValue("targets").jsonArray.map { it.jsonObject }
        val notification = notifications.single { it.getValue("mode").jsonPrimitive.content == "PUSH" }

        assertEquals("BLOCK", body.getValue("type").jsonPrimitive.content)
        assertTrue(body.getValue("desc").jsonPrimitive.content.contains(PHONE_HEARTBEAT_OWNER_MARKER))
        assertEquals(41, condition.getValue("aId").jsonPrimitive.content.toInt())
        assertEquals(7, condition.getValue("sId").jsonPrimitive.content.toInt())
        assertEquals(9, condition.getValue("cId").jsonPrimitive.content.toInt())
        assertEquals("RESET", delay.getValue("mode").jsonPrimitive.content)
        assertEquals(PHONE_HEARTBEAT_TIMEOUT_MS, delay.getValue("time").jsonPrimitive.content.toLong())
        assertEquals("PUSH", notification.getValue("mode").jsonPrimitive.content)
        assertTrue(notifications.any { it.getValue("mode").jsonPrimitive.content == "MESSAGE" })
    }

    @Test
    fun `scenario detail request expands block graph`() {
        val get = scenarioDetailRequest("18")
            .getValue("scenario").jsonObject
            .getValue("get").jsonObject

        assertEquals("18", get.getValue("index").jsonPrimitive.content)
        assertEquals("data", get.getValue("expand").jsonPrimitive.content)
    }

    @Test
    fun `expanded scenario data accepts encoded string and json object`() {
        val data = heartbeatScenarioData(binding, target)

        assertEquals(data.toString(), scenarioDataText(JsonPrimitive(data.toString())))
        assertEquals(data.toString(), scenarioDataText(data))
    }

    @Test
    fun `notification services use extension api supported by current hub`() {
        val list = notificationServiceListRequest()
            .getValue("extension").jsonObject
            .getValue("list").jsonObject

        assertEquals("NOTIFICATION", list.getValue("bundleType").jsonPrimitive.content)
    }

    @Test
    fun `semantic verification accepts hub-added fields and rejects stale target`() {
        val canonical = scenarioBody(binding, target).getValue("data").jsonPrimitive.content
        val current = record(canonical)
        val stale = canonical.replace("\"cId\":9", "\"cId\":10")

        assertTrue(heartbeatScenarioIsCurrent(current, binding, target))
        assertFalse(heartbeatScenarioIsCurrent(record(stale), binding, target))
        assertFalse(heartbeatScenarioIsCurrent(current.copy(active = false), binding, target))
        assertFalse(
            heartbeatScenarioIsCurrent(
                current.copy(description = "Похожий пользовательский сценарий"),
                binding,
                target,
            ),
        )
    }

    @Test
    fun `old binding type metadata has safe SprutHub fallbacks`() {
        val legacyTarget = target.copy(serviceType = "", characteristicType = "")
        val data = heartbeatScenarioData(binding, legacyTarget)
        val condition = data.getValue("targets").jsonArray.single().jsonObject
            .getValue("if").jsonObject
            .getValue("conditions").jsonArray.single().jsonObject

        assertEquals("C_Option", condition.getValue("hs").jsonPrimitive.content)
        assertEquals("C_GenericInteger", condition.getValue("hc").jsonPrimitive.content)
    }

    @Test
    fun `paused scenario keeps ownership and target but is inactive`() {
        val body = scenarioBody(binding, target, active = false)
        val scenario = record(body.getValue("data").jsonPrimitive.content).copy(
            active = body.getValue("active").jsonPrimitive.content.toBoolean(),
        )

        assertFalse(scenario.active == true)
        assertFalse(heartbeatScenarioIsCurrent(scenario, binding, target))
        assertTrue(scenario.isOwned)
    }

    private fun record(data: String) = ScenarioRecord(
        index = "5",
        name = "SprutHub Helper · Контроль телефона",
        description = PHONE_HEARTBEAT_OWNER_MARKER,
        active = true,
        onStart = true,
        type = "BLOCK",
        data = data,
    )
}
