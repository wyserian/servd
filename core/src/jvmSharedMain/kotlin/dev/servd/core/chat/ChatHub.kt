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
    private val live = LiveRegistry(newId = { UUID.randomUUID().toString() })
    private val liveLock = Mutex()
    private var playback: Playback? = null // what the host is playing for everyone
    private val playbackLock = Mutex()
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
        sendTo(id, liveList()) // who is already live
        sendTo(id, mediaSession()) // and what is playing, so a late joiner catches up
    }

    suspend fun onChat(id: String, text: String) {
        val name = conns[id]?.name ?: return // must have said hello first
        val clean = text.trim()
        if (clean.isEmpty()) return
        broadcast(ChatEvent(id, name, clean.take(4000), System.currentTimeMillis()))
    }

    suspend fun onDisconnect(id: String) {
        conns.remove(id)
        val gone = liveLock.withLock { live.removePeer(id) }
        for ((streamId, ownerId) in gone.leftFrom) sendTo(ownerId, LiveViewer(streamId, id, joined = false))
        if (gone.changed) broadcastLive()
        endPlaybackIf { it.ownerId == id } // the host who started it left
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
        endPlaybackIf { it.fileId == id }
    }

    suspend fun announceFilesRemoved(ids: List<String>) {
        if (ids.isNotEmpty()) broadcast(FilesRemoved(ids))
        endPlaybackIf { it.fileId in ids }
    }

    suspend fun announceFilesCleared(byName: String) {
        broadcast(FilesCleared(byName))
        endPlaybackIf { it.fileId != null } // links aren't shared files
    }

    // ---- play a shared file for everyone (the caller checks the host and the file)

    suspend fun onMediaPlay(id: String, fileId: String?, src: String, name: String, kind: String) {
        if (conns[id]?.name == null) return
        playbackLock.withLock { playback = Playback(fileId, src, name, kind, id, now()) }
        broadcast(mediaSession())
    }

    /** Only the device that started playback steers it. */
    suspend fun onMediaState(id: String, playing: Boolean, position: Double, sync: Boolean) {
        val changed = playbackLock.withLock {
            val p = playback?.takeIf { it.ownerId == id && position.isFinite() } ?: return@withLock false
            playback = p.update(playing, position, sync, now())
            true
        }
        if (changed) broadcast(mediaSession())
    }

    suspend fun onMediaStop(id: String) = endPlaybackIf { it.ownerId == id }

    private suspend fun endPlaybackIf(predicate: (Playback) -> Boolean) {
        val ended = playbackLock.withLock { playback?.takeIf(predicate)?.also { playback = null } != null }
        if (ended) broadcast(MediaSession(null))
    }

    private suspend fun mediaSession() = MediaSession(playbackLock.withLock { playback?.info(now()) })

    private fun now() = System.currentTimeMillis()

    // ---- live streams: the hub tracks who is live and relays WebRTC setup; media is peer to peer

    suspend fun onLiveStart(id: String, kind: String) {
        if (conns[id]?.name == null) return // must have said hello first
        if (liveLock.withLock { live.start(id, kind) }) broadcastLive()
    }

    suspend fun onLiveStop(id: String) {
        if (liveLock.withLock { live.stop(id) }) broadcastLive() // viewers drop it from the list
    }

    suspend fun onLiveWatch(id: String, streamId: String) {
        when (val r = liveLock.withLock { live.watch(streamId, id) }) {
            is LiveRegistry.Watch.Ok -> {
                sendTo(r.ownerId, LiveViewer(streamId, id, joined = true))
                broadcastLive()
            }
            LiveRegistry.Watch.Full -> sendTo(id, LiveFull(streamId))
            LiveRegistry.Watch.Invalid -> {}
        }
    }

    suspend fun onLiveLeave(id: String, streamId: String) {
        val ownerId = liveLock.withLock { live.leave(streamId, id) } ?: return
        sendTo(ownerId, LiveViewer(streamId, id, joined = false))
        broadcastLive()
    }

    /** Relay a setup message, only between a stream's owner and its viewers, and only small ones. */
    suspend fun onSignal(from: String, msg: SignalSend) {
        if (msg.data.toString().length > MAX_SIGNAL_CHARS) return
        if (liveLock.withLock { live.canRelay(msg.stream, from, msg.to) }) {
            sendTo(msg.to, SignalEvent(from, msg.stream, msg.data))
        }
    }

    private suspend fun liveList(): LiveList {
        val names = conns.mapValues { it.value.name }
        return liveLock.withLock { LiveList(live.snapshot { names[it] }, live.maxViewers) }
    }

    private suspend fun broadcastLive() = broadcast(liveList())

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

    private companion object {
        /** SDP offers/answers are a few KB; anything far bigger isn't WebRTC setup. */
        const val MAX_SIGNAL_CHARS = 64_000
    }
}
