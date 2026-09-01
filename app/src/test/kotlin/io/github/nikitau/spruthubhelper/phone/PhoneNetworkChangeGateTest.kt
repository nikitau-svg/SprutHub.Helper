package io.github.nikitau.spruthubhelper.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNetworkChangeGateTest {
    private val wifi = PhoneNetworkFingerprint(
        connectionType = "Wi‑Fi",
        metered = false,
        validated = true,
        localAddress = "192.0.2.10",
    )

    @Test
    fun `primed state ignores repeated capability callbacks`() {
        val gate = PhoneNetworkChangeGate()

        gate.prime(wifi)

        repeat(20) { assertFalse(gate.hasChanged(wifi)) }
    }

    @Test
    fun `failed delivery does not consume a real network change`() {
        val gate = PhoneNetworkChangeGate()
        gate.prime(wifi)
        val cellular = wifi.copy(
            connectionType = "Мобильная сеть",
            metered = true,
            localAddress = "198.51.100.20",
        )

        assertTrue(gate.hasChanged(cellular))
        assertTrue(gate.hasChanged(cellular))

        gate.commit(cellular)
        assertFalse(gate.hasChanged(cellular))
    }

    @Test
    fun `every published network field is part of the fingerprint`() {
        val changedStates = listOf(
            wifi.copy(connectionType = "VPN · Wi‑Fi"),
            wifi.copy(metered = true),
            wifi.copy(validated = false),
            wifi.copy(localAddress = "192.0.2.11"),
        )

        changedStates.forEach { changed ->
            val gate = PhoneNetworkChangeGate().apply { prime(wifi) }
            assertTrue(changed.toString(), gate.hasChanged(changed))
        }
    }

    @Test
    fun `unprimed gate treats the first observation as a change`() {
        assertTrue(PhoneNetworkChangeGate().hasChanged(wifi))
    }

    @Test
    fun `unchanged network callbacks are removed without dropping another event`() {
        val mixed = linkedSetOf(
            PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED,
            PhoneSyncTrigger.SCREEN_OFF,
            PhoneSyncTrigger.NETWORK_ADDRESS_CHANGED,
        )

        assertEquals(
            setOf(PhoneSyncTrigger.SCREEN_OFF),
            filterPhoneNetworkTriggers(mixed, networkChanged = false),
        )
        assertEquals(mixed, filterPhoneNetworkTriggers(mixed, networkChanged = true))
        assertTrue(
            filterPhoneNetworkTriggers(
                setOf(PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED),
                networkChanged = false,
            ).isEmpty(),
        )
    }
}
