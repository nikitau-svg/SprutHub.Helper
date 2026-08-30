package io.github.nikitau.spruthubhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SprutEndpointTest {
    @Test
    fun normalizesFriendlyLocalAndCloudAddresses() {
        assertEquals(
            "ws://spruthub.local/spruthub",
            normalizeSprutEndpoint("spruthub.local", secureByDefault = false),
        )
        assertEquals(
            "ws://192.168.10.15:7777/spruthub",
            normalizeSprutEndpoint("http://192.168.10.15:7777", secureByDefault = false),
        )
        assertEquals(
            "wss://web.spruthub.ru/spruthub",
            normalizeSprutEndpoint("https://web.spruthub.ru", secureByDefault = true),
        )
        assertEquals(
            "wss://example.net/custom/socket",
            normalizeSprutEndpoint("wss://example.net/custom/socket", secureByDefault = true),
        )
    }

    @Test
    fun recognizesPrivateLanWithoutTreatingPublicHostsAsLocal() {
        listOf(
            "10.0.0.4",
            "172.16.5.2",
            "172.31.255.254",
            "192.168.50.3",
            "100.100.100.100",
            "spruthub.local",
            "spruthub-home",
            "fd12::42",
        ).forEach { assertTrue(it, isPrivateLanHost(it)) }

        listOf(
            "8.8.8.8",
            "172.32.0.1",
            "web.spruthub.ru",
            "example.net",
        ).forEach { assertFalse(it, isPrivateLanHost(it)) }
    }

    @Test
    fun repairsOnlyDuplicatedHexHubSerialsFromEarlyBuilds() {
        assertEquals(
            "ABCDEF0123456789",
            normalizeHubSerial("ABCDEF0123456789abcdef0123456789"),
        )
        assertEquals("ABCDEF0123456789", normalizeHubSerial("  ABCDEF0123456789  "))
        assertEquals(
            "not-a-serial-123not-a-serial-123",
            normalizeHubSerial("not-a-serial-123not-a-serial-123"),
        )
    }
}
