package io.github.nikitau.spruthubhelper.phone

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Only the network values that SprutHub Helper actually publishes.
 *
 * Android may call onCapabilitiesChanged for bandwidth estimates and other
 * metadata that is irrelevant to the virtual phone device. Comparing the full
 * NetworkCapabilities object would therefore create a feedback loop where our
 * own traffic causes another complete phone sync.
 */
internal data class PhoneNetworkFingerprint(
    val connectionType: String,
    val metered: Boolean,
    val validated: Boolean,
    val localAddress: String?,
)

internal val PHONE_NETWORK_TRIGGERS = setOf(
    PhoneSyncTrigger.NETWORK_AVAILABLE,
    PhoneSyncTrigger.NETWORK_LOST,
    PhoneSyncTrigger.NETWORK_CAPABILITIES_CHANGED,
    PhoneSyncTrigger.NETWORK_ADDRESS_CHANGED,
)

internal fun filterPhoneNetworkTriggers(
    triggers: Set<PhoneSyncTrigger>,
    networkChanged: Boolean,
): Set<PhoneSyncTrigger> = if (networkChanged) {
    triggers
} else {
    triggers.filterTo(linkedSetOf()) { it !in PHONE_NETWORK_TRIGGERS }
}

internal class PhoneNetworkChangeGate {
    private var initialized = false
    private var committed: PhoneNetworkFingerprint? = null

    @Synchronized
    fun prime(fingerprint: PhoneNetworkFingerprint) {
        committed = fingerprint
        initialized = true
    }

    @Synchronized
    fun hasChanged(fingerprint: PhoneNetworkFingerprint): Boolean =
        !initialized || committed != fingerprint

    @Synchronized
    fun commit(fingerprint: PhoneNetworkFingerprint) {
        committed = fingerprint
        initialized = true
    }
}

internal fun ConnectivityManager.phoneNetworkFingerprint(): PhoneNetworkFingerprint {
    val network = activeNetwork
    val capabilities = network?.let(::getNetworkCapabilities)
    val linkProperties = network?.let(::getLinkProperties)
    return PhoneNetworkFingerprint(
        connectionType = phoneConnectionType(capabilities),
        metered = isActiveNetworkMetered,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        localAddress = phoneLocalAddress(linkProperties),
    )
}

internal fun phoneConnectionType(capabilities: NetworkCapabilities?): String {
    if (capabilities == null) return "Нет сети"
    return buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Мобильная сеть")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("Bluetooth")
    }.joinToString(" · ").ifBlank { "Другая сеть" }
}

internal fun phoneLocalAddress(linkProperties: LinkProperties?): String? = linkProperties
    ?.linkAddresses
    ?.map { it.address }
    ?.filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
    ?.sortedByDescending { it is Inet4Address }
    ?.firstOrNull()
    ?.hostAddress
    ?.substringBefore('%')
