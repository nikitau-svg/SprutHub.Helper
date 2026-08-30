package io.github.nikitau.spruthubhelper.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConfigSecurityTest {
    @Test
    fun serializationNeverExposesPasswordsAndToStringRedactsCredentials() {
        val config = HubConfig(
            serial = "private-serial-needle",
            email = "private-email-needle@example.test",
            localPassword = "local-secret-needle",
            cloudPassword = "cloud-secret-needle",
            password = "legacy-secret-needle",
        )

        val encoded = Json.encodeToString(config)
        val description = config.toString()

        listOf("private-serial-needle", "private-email-needle@example.test").forEach { personalValue ->
            assertFalse(description.contains(personalValue))
        }
        listOf(
            "local-secret-needle",
            "cloud-secret-needle",
            "legacy-secret-needle",
        ).forEach { secret ->
            assertFalse(encoded.contains(secret))
            assertFalse(description.contains(secret))
        }
        assertFalse(encoded.contains("localPassword"))
        assertFalse(encoded.contains("cloudPassword"))
        assertFalse(encoded.contains("password"))
        assertTrue(description.contains("<redacted>"))
    }
}
