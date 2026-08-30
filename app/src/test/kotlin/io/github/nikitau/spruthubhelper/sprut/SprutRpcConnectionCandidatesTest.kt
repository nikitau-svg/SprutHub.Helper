package io.github.nikitau.spruthubhelper.sprut

import io.github.nikitau.spruthubhelper.data.ConnectionMode
import io.github.nikitau.spruthubhelper.data.HubConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SprutRpcConnectionCandidatesTest {
    @Test
    fun autoUsesLocalThenCloudWithIndependentPasswords() {
        val candidates = connectionCandidates(config(ConnectionMode.AUTO))

        assertEquals(2, candidates.size)
        assertEquals("ws://local.test/spruthub", candidates[0].url)
        assertTrue(candidates[0].isLocal)
        assertEquals("local-password", candidates[0].password)
        assertEquals("wss://cloud.test/spruthub", candidates[1].url)
        assertFalse(candidates[1].isLocal)
        assertEquals("cloud-password", candidates[1].password)
    }

    @Test
    fun autoUsesCloudFirstWhenCurrentNetworkIsCellular() {
        val candidates = connectionCandidates(config(ConnectionMode.AUTO), preferCloud = true)

        assertEquals(listOf(false, true), candidates.map { it.isLocal })
        assertEquals(listOf("cloud-password", "local-password"), candidates.map { it.password })
    }

    @Test
    fun explicitModesOnlyExposeTheirMatchingCredential() {
        val local = connectionCandidates(config(ConnectionMode.LOCAL)).single()
        val cloud = connectionCandidates(config(ConnectionMode.CLOUD)).single()

        assertTrue(local.isLocal)
        assertEquals("local-password", local.password)
        assertFalse(cloud.isLocal)
        assertEquals("cloud-password", cloud.password)
    }

    @Test
    fun legacySinglePasswordRemainsACompatibleFallback() {
        val config = HubConfig(
            mode = ConnectionMode.AUTO,
            localUrl = "ws://local.test/spruthub",
            cloudUrl = "wss://cloud.test/spruthub",
            password = "legacy-password",
        )

        val candidates = connectionCandidates(config)

        assertEquals(listOf("legacy-password", "legacy-password"), candidates.map { it.password })
    }

    @Test
    fun identicalUrlsStillKeepBothCredentialAttemptsInAutoMode() {
        val candidates = connectionCandidates(
            config(ConnectionMode.AUTO).copy(
                localUrl = "wss://same.test/spruthub",
                cloudUrl = "wss://same.test/spruthub",
            ),
        )

        assertEquals(2, candidates.size)
        assertEquals(listOf("local-password", "cloud-password"), candidates.map { it.password })
    }

    @Test
    fun candidateStringRepresentationRedactsItsPassword() {
        val description = connectionCandidates(config(ConnectionMode.LOCAL)).single().toString()

        assertFalse(description.contains("local-password"))
        assertTrue(description.contains("<redacted>"))
    }

    private fun config(mode: ConnectionMode) = HubConfig(
        mode = mode,
        localUrl = "ws://local.test/spruthub",
        cloudUrl = "wss://cloud.test/spruthub",
        localPassword = "local-password",
        cloudPassword = "cloud-password",
    )
}
