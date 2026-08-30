package io.github.nikitau.spruthubhelper.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConfigSecurityTest {
    @Test
    fun serializationAndToStringNeverExposePasswords() {
        val config = HubConfig(
            localPassword = "local-secret-needle",
            cloudPassword = "cloud-secret-needle",
            password = "legacy-secret-needle",
        )

        val encoded = Json.encodeToString(config)
        val description = config.toString()

        listOf("local-secret-needle", "cloud-secret-needle", "legacy-secret-needle").forEach { secret ->
            assertFalse(encoded.contains(secret))
            assertFalse(description.contains(secret))
        }
        assertFalse(encoded.contains("localPassword"))
        assertFalse(encoded.contains("cloudPassword"))
        assertFalse(encoded.contains("password"))
        assertTrue(description.contains("<redacted>"))
    }
}
