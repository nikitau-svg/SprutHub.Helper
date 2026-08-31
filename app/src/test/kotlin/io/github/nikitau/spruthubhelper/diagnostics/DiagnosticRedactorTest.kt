package io.github.nikitau.spruthubhelper.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `structured sensitive fields are always removed`() {
        val source = DiagnosticEvent(
            category = DiagnosticCategory.SYNC,
            event = "Health sync for owner@example.com",
            outcome = DiagnosticOutcome.FAILED,
            reason = "password=hunter2 token=abc.123 serial=HUB-7788 at 55.755800, 37.617300",
            details = mapOf(
                "password" to "hunter2",
                "access_token" to "token-value",
                "hub_id" to "hub-personal-id",
                "serialNumber" to "HUB-7788",
                "email" to "owner@example.com",
                "latitude" to "55.7558",
                "longitude" to "37.6173",
                "heart_rate" to "81",
                "steps" to "12876",
                "safe_attempt" to "2",
            ),
        )

        val redacted = DiagnosticRedactor.redact(source)
        val rendered = listOf(redacted.event, redacted.reason, redacted.details.toString()).joinToString()

        assertFalse(rendered.contains("owner@example.com"))
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("token-value"))
        assertFalse(rendered.contains("hub-personal-id"))
        assertFalse(rendered.contains("HUB-7788"))
        assertFalse(rendered.contains("55.7558"))
        assertFalse(rendered.contains("37.6173"))
        assertFalse(rendered.contains("12876"))
        assertFalse(rendered.contains("81"))
        assertEquals("2", redacted.details["safe_attempt"])
        assertTrue(redacted.details.filterKeys { it != "safe_attempt" }.values.all { it == DiagnosticRedactor.REDACTED })
    }

    @Test
    fun `url authority userinfo private address and path identifiers are hidden`() {
        val source = "connect ws://user:pass@192.168.1.44:80/hubs/HUB-SERIAL-9988?token=topsecret"

        val redacted = DiagnosticRedactor.redactText(source)

        assertTrue(redacted.contains("ws://${DiagnosticRedactor.REDACTED}/…"))
        assertFalse(redacted.contains("user"))
        assertFalse(redacted.contains("pass"))
        assertFalse(redacted.contains("192.168.1.44"))
        assertFalse(redacted.contains("HUB-SERIAL-9988"))
        assertFalse(redacted.contains("topsecret"))
    }

    @Test
    fun `russian health coordinate and credential labels are redacted`() {
        val source = "пароль: qwerty; пульс: 80; вес=72.4; широта=55.7558; долгота=37.6173; " +
            "серийный номер=ABC-123"

        val redacted = DiagnosticRedactor.redactText(source)

        listOf("qwerty", "80", "72.4", "55.7558", "37.6173", "ABC-123").forEach { secret ->
            assertFalse("Unexpected value in: $redacted", redacted.contains(secret))
        }
        assertTrue(redacted.contains(DiagnosticRedactor.REDACTED))
    }

    @Test
    fun `raw host errors do not disclose hostnames or addresses`() {
        val source = "failed to connect to hub.owner.local:8080 / 10.0.0.42"

        val redacted = DiagnosticRedactor.redactText(source)

        assertFalse(redacted.contains("hub.owner.local"))
        assertFalse(redacted.contains("10.0.0.42"))
    }

    @Test
    fun `protocol error identifiers are removed from json text`() {
        val source = "RPC error: {\"aId\":42,\"serviceId\":\"svc-7\",\"cId\":915,\"message\":\"denied\"}"

        val redacted = DiagnosticRedactor.redactText(source)

        listOf("42", "svc-7", "915").forEach { identifier ->
            assertFalse("Unexpected identifier in: $redacted", redacted.contains(identifier))
        }
        assertTrue(redacted.contains("denied"))
    }
}
