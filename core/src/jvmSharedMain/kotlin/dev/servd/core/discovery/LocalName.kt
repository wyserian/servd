package dev.servd.core.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.impl.JmDNSImpl

/**
 * Answers mDNS lookups for "<name>.local" (servd.local by default), so devices that resolve
 * .local names - iPhone/iPad, macOS, Windows 10+, most Linux - can open the hub by name instead of
 * its IP. If another device already answers to that name, mDNS probing moves this one to the next
 * free name ("servd-1.local", ...); [current] reports the name actually in use. (Two hubs on the
 * same machine share an address, so they answer identically and don't count as a clash.)
 *
 * JmDNS-based, so it runs on desktop and Android alike. Desktop also registers its _servd._tcp
 * service on the same responder ([jmdns]); Android advertises the service via NsdManager.
 */
class LocalName {
    /** The responder (core-internal: desktop registers its service on it). */
    @Volatile internal var jmdns: JmDNS? = null
        private set

    /**
     * Start answering for [name] (without ".local") on [bindAddress]; null [name] answers only for
     * the machine's own host name (no friendly name). Waits briefly for the name to be claimed.
     */
    fun start(bindAddress: InetAddress, name: String?) {
        val jm = JmDNS.create(bindAddress, name)
        jmdns = jm
        // Probing takes ~1 s; after it, a clash has already been resolved to a free name.
        runCatching { (jm as? JmDNSImpl)?.waitForAnnounced(ANNOUNCE_WAIT_MS) }
    }

    /** The name being answered, e.g. "servd.local", or null if not running. */
    val current: String? get() = jmdns?.hostName?.trimEnd('.')?.ifBlank { null }

    fun stop() {
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
    }

    companion object {
        const val DEFAULT = "servd"
        private const val ANNOUNCE_WAIT_MS = 4000L

        /** A valid single DNS label: 1-63 of a-z, 0-9 and "-", not starting or ending with "-". */
        fun isValid(name: String): Boolean = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?").matches(name)
    }
}
