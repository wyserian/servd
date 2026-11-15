package dev.servd.core.chat

/**
 * Tracks who is connected to the hub. Pure Kotlin (no sockets, no threads) so the presence
 * bookkeeping is unit-testable; the ChatHub guards access and owns the actual WebSocket
 * sessions. A peer becomes visible in the roster only once it has said hello (a name).
 */
class PeerRegistry {
    private val peers = LinkedHashMap<String, Peer>()
    private val conns = HashMap<String, MutableSet<String>>() // peer id -> its open connections (tabs)

    /** Connection [connId] says hello as [peer] (adds or renames it). True if the device is new. */
    fun join(peer: Peer, connId: String): Boolean {
        val isNew = !peers.containsKey(peer.id)
        peers[peer.id] = peer
        conns.getOrPut(peer.id) { mutableSetOf() } += connId
        return isNew
    }

    /** Connection [connId] of [peerId] closed. Returns the peer if that was its last one (it leaves the roster). */
    fun leave(peerId: String, connId: String): Peer? {
        val open = conns[peerId] ?: return null
        open -= connId
        if (open.isNotEmpty()) return null
        conns.remove(peerId)
        return peers.remove(peerId)
    }

    fun get(id: String): Peer? = peers[id]

    fun contains(id: String): Boolean = peers.containsKey(id)

    /** Insertion-ordered snapshot of the current roster. */
    fun snapshot(): List<Peer> = peers.values.toList()

    val size: Int get() = peers.size
}

private val DEVICE_ID = Regex("[A-Za-z0-9-]{8,64}")

/**
 * The roster id for a connection: tabs of one browser (same [deviceId], same address) share one
 * entry. Tied to the address so a copied device id can't merge into another device; a missing or
 * malformed id falls back to the connection's own id.
 */
fun peerIdFor(connId: String, deviceId: String?, address: String): String =
    if (deviceId != null && DEVICE_ID.matches(deviceId)) "$deviceId@$address" else connId
