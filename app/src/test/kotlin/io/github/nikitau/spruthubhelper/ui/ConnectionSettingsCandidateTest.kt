package io.github.nikitau.spruthubhelper.ui

import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HubConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionSettingsCandidateTest {
    private val stored = HubConfig(
        mode = ConnectionMode.AUTO,
        localUrl = "ws://old-hub.local/spruthub",
        cloudUrl = "wss://old-cloud.example.invalid/spruthub",
        serial = "FIXTURE-HUB",
        email = "fixture@example.invalid",
        localPassword = "stored-local-password",
        cloudPassword = "stored-cloud-password",
    )

    @Test
    fun `blank password fields preserve two independent stored passwords`() {
        val candidate = buildConnectionSettingsCandidate(
            stored = stored,
            mode = ConnectionMode.AUTO,
            localUrl = "new-hub.local",
            cloudUrl = "https://new-cloud.example.invalid",
            serial = " FIXTURE-HUB ",
            email = " fixture@example.invalid ",
            newLocalPassword = "",
            newCloudPassword = "",
        )

        assertEquals("stored-local-password", candidate.config.localPassword)
        assertEquals("stored-cloud-password", candidate.config.cloudPassword)
        assertNull(candidate.passwordUpdate.localPassword)
        assertNull(candidate.passwordUpdate.cloudPassword)
        assertEquals("ws://new-hub.local/spruthub", candidate.config.localUrl)
        assertEquals("wss://new-cloud.example.invalid/spruthub", candidate.config.cloudUrl)
        assertEquals("FIXTURE-HUB", candidate.config.serial)
        assertEquals("fixture@example.invalid", candidate.config.email)
    }

    @Test
    fun `changing cloud password does not overwrite local password`() {
        val candidate = buildConnectionSettingsCandidate(
            stored = stored,
            mode = ConnectionMode.CLOUD,
            localUrl = stored.localUrl,
            cloudUrl = stored.cloudUrl,
            serial = stored.serial,
            email = stored.email,
            newLocalPassword = "",
            newCloudPassword = "replacement-cloud-password",
        )

        assertEquals("stored-local-password", candidate.config.localPassword)
        assertEquals("replacement-cloud-password", candidate.config.cloudPassword)
        assertNull(candidate.passwordUpdate.localPassword)
        assertEquals("replacement-cloud-password", candidate.passwordUpdate.cloudPassword)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid form is rejected before network verification`() {
        buildConnectionSettingsCandidate(
            stored = stored,
            mode = ConnectionMode.LOCAL,
            localUrl = "",
            cloudUrl = stored.cloudUrl,
            serial = stored.serial,
            email = stored.email,
            newLocalPassword = "",
            newCloudPassword = "",
        )
    }
}
