package io.github.wltbagent.rtspcamera.util

import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN address discovery for the connect-info UI. */
object NetUtils {

    /** First site-local IPv4 address, or null when offline. */
    fun lanIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
