package dev.servd.core.chat

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The real-time chat + presence hub. Owns the live WebSocket sessions and drives the pure
 * [PeerRegistry]. A connection is registered on connect but only appears in the roster once it
 * has said [Hello] (so it has a name). All outbound frames go through a per-connection lock so
 * concurrent broadcasts to the same socket never interleave.
 */
class ChatHub(private val serverName: String) {

    private class Conn(
        val id: String,
        val session: WebSocketSession,
        val address: String,
        @Volatile var name: String? = null,
        val sendLock: Mutex = Mutex(),
    )

    private val conns = ConcurrentHashMap<String, Conn>()
    private val registry = PeerRegistry()
    private val registryLock = Mutex()
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Register a new connection and send it a Welcome. Not in the roster until hello. */
    suspend fun onConnect(session: WebSocketSession, address: String): String {
        val id = UUID.randomUUID().toString()
        conns[id] = Conn(id, session, address)
        sendTo(id, Welcome(id, serverName))
        return id
    }

    suspend fun onHello(id: String, name: String) {
        val conn = conns[id] ?: return
        val clean = name.trim().ifBlank { "guest" }.take(40)
        conn.name = clean
        val peer = Peer(id, clean, conn.address)
        registryLock.withLock { registry.put(peer) }
        broadcast(PresenceEvent("join", peer))
        broadcastRoster()
    }

    suspend fun onChat(id: String, text: String) {
        val name = conns[id]?.name ?: return // must have said hello first
        val clean = text.trim()
        if (clean.isEmpty()) return
        broadcast(ChatEvent(id, name, clean.take(4000), System.currentTimeMillis()))
    }

    suspend fun onDisconnect(id: String) {
        conns.remove(id)
        val peer = registryLock.withLock { registry.remove(id) } ?: return
        broadcast(PresenceEvent("leave", peer))
        broadcastRoster()
    }

    /** Number of live dashboard connections. */
    fun connectionCount(): Int = conns.size

    /** Close every connection from an address matching [predicate] (e.g. sign-out on a PIN change). */
    suspend fun closeWhere(predicate: (address: String) -> Boolean) {
        for (conn in conns.values) if (predicate(conn.address)) {
            runCatching { conn.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "signed out")) }
        }
    }

    /** Broadcast that a file was shared, so every connected dashboard updates live. */
    suspend fun announceFile(meta: FileMeta) {
        broadcast(FileShared(meta))
    }

    /** Clear the chat for everyone (chat is not stored server-side; this wipes live logs). */
    suspend fun onClearChat(id: String) {
        val name = conns[id]?.name ?: return
        broadcast(ChatCleared(name))
    }

    suspend fun announceFileRemoved(id: String) {
        broadcast(FileRemoved(id))
    }

    suspend fun announceFilesCleared(byName: String) {
        broadcast(FilesCleared(byName))
    }

    /** Parse an inbound client frame; null if it isn't a valid message. */
    fun parseClient(text: String): ClientMessage? =
        try { json.decodeFromString<ClientMessage>(text) } catch (_: Exception) { null }

    private suspend fun broadcastRoster() {
        val peers = registryLock.withLock { registry.snapshot() }
        broadcast(Roster(peers))
    }

    private suspend fun broadcast(msg: ServerMessage) {
        val text = json.encodeToString<ServerMessage>(msg)
        for (conn in conns.values) sendRaw(conn, text)
    }

    private suspend fun sendTo(id: String, msg: ServerMessage) {
        val conn = conns[id] ?: return
        sendRaw(conn, json.encodeToString<ServerMessage>(msg))
    }

    private suspend fun sendRaw(conn: Conn, text: String) {
        try {
            conn.sendLock.withLock { conn.session.send(Frame.Text(text)) }
        } catch (_: Exception) {
            // Session died mid-broadcast; disconnect handling removes it.
        }
    }
}
