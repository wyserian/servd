package dev.servd.core.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** The DNS-SD service type servd advertises itself under. */
const val SERVD_MDNS_TYPE = "_servd._tcp.local."

/** A servd hub found on the local network via mDNS. */
data class DiscoveredHub(
    val name: String,
    val host: String,
    val port: Int,
    val version: String?,
    val fingerprint: String?,
) {
    val url: String get() = "https://$host:$port"
}

/**
 * Advertises this hub as an mDNS/DNS-SD service so other devices can find it without being
 * told an IP, on the same responder that answers for its .local name ([LocalName]).
 * Desktop-only; the Android host advertises the service via NsdManager.
 */
class MdnsAdvertiser {
    private val localName = LocalName()

    /** The .local name this hub answers to (e.g. "servd.local"), once [start] has run. */
    val hostName: String? get() = localName.current

    /** Answer for [hostName] (null = only the machine's own name) and register the service. */
    fun start(bindAddress: InetAddress, hostName: String?, instanceName: String, port: Int, txt: Map<String, String>) {
        localName.start(bindAddress, hostName)
        val info = ServiceInfo.create(SERVD_MDNS_TYPE, instanceName, port, 0, 0, HashMap(txt))
        localName.jmdns?.registerService(info)
    }

    fun stop() = localName.stop()
}

/** Browses the local network for servd hubs for a short window and returns what it found. */
class MdnsBrowser {
    fun discover(durationMillis: Long = 3000, bindAddress: InetAddress? = null): List<DiscoveredHub> {
        val jm = if (bindAddress != null) JmDNS.create(bindAddress) else JmDNS.create()
        val found = LinkedHashMap<String, DiscoveredHub>()
        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                jm.requestServiceInfo(event.type, event.name, 1200)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                found.remove(event.name)
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.hostAddresses.firstOrNull()
                    ?: return
                found[event.name] = DiscoveredHub(
                    name = event.name,
                    host = host,
                    port = info.port,
                    version = info.getPropertyString("version"),
                    fingerprint = info.getPropertyString("fingerprint"),
                )
            }
        }
        jm.addServiceListener(SERVD_MDNS_TYPE, listener)
        Thread.sleep(durationMillis)
        jm.removeServiceListener(SERVD_MDNS_TYPE, listener)
        jm.close()
        return found.values.toList()
    }
}
