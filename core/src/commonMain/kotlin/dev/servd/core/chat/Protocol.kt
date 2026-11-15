package dev.servd.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** A device/person connected to the hub. */
@Serializable
data class Peer(
    val id: String,
    val name: String,
    val address: String,
)

/**
 * WebSocket protocol between the dashboard and the hub. Sealed hierarchies serialize with a
 * `"type"` discriminator (e.g. {"type":"hello","name":"pixel-7"}).
 */
@Serializable
sealed interface ClientMessage

@Serializable
@SerialName("hello")
data class Hello(val name: String, val deviceId: String? = null) : ClientMessage // tabs of one browser share deviceId

@Serializable
@SerialName("chat")
data class ChatSend(val text: String) : ClientMessage

@Serializable
@SerialName("clearChat")
data object ClearChat : ClientMessage

// ---- Live streams (WebRTC). Media goes peer to peer; the hub only keeps the list of who is live
// and relays the WebRTC setup messages (SDP offers/answers, ICE candidates) between two peers.

/** Go live; [kind] is "camera", "screen" or "audio". One stream per connection. */
@Serializable
@SerialName("liveStart")
data class LiveStart(val kind: String) : ClientMessage

/** End this connection's stream. */
@Serializable
@SerialName("liveStop")
data object LiveStop : ClientMessage

/** Ask to watch stream [id]; the owner is told and starts the WebRTC setup. */
@Serializable
@SerialName("liveWatch")
data class LiveWatch(val id: String) : ClientMessage

@Serializable
@SerialName("liveLeave")
data class LiveLeave(val id: String) : ClientMessage

/** A WebRTC setup message for peer [to] about stream [stream]; [data] is passed through as-is. */
@Serializable
@SerialName("signal")
data class SignalSend(val to: String, val stream: String, val data: JsonElement) : ClientMessage

// ---- Play a shared video/audio file for everyone (host only). Each device streams the file from
// the hub; with sync on, the host's play/pause/seek is mirrored on every device.

@Serializable
@SerialName("mediaPlay")
data class MediaPlay(val fileId: String) : ClientMessage

/** Play a link instead: a YouTube video or a direct https video/audio URL. */
@Serializable
@SerialName("mediaPlayUrl")
data class MediaPlayUrl(val url: String) : ClientMessage

/** The host's player state: sent on play/pause/seek and every few seconds while playing. */
@Serializable
@SerialName("mediaState")
data class MediaState(val playing: Boolean, val position: Double, val sync: Boolean) : ClientMessage

@Serializable
@SerialName("mediaStop")
data object MediaStop : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(val selfId: String, val serverName: String, val peerId: String? = null) : ServerMessage // peerId: this device's roster entry

@Serializable
@SerialName("roster")
data class Roster(val peers: List<Peer>) : ServerMessage

@Serializable
@SerialName("chat")
data class ChatEvent(
    val fromId: String,
    val fromName: String,
    val text: String,
    val ts: Long,
) : ServerMessage

@Serializable
@SerialName("presence")
data class PresenceEvent(
    /** "join" or "leave". */
    val event: String,
    val peer: Peer,
) : ServerMessage

/** A file that has been shared to the hub. */
@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val contentType: String? = null,
    val fromName: String,
    val ts: Long,
    /**
     * For a file dropped as part of a folder: its path inside that folder, starting with the
     * folder's name (e.g. "photos/2024/a.jpg"). Null for a loose file.
     */
    val path: String? = null,
    /** Groups the files of one folder drop (shown as one folder row); null for a loose file. */
    val batch: String? = null,
    /** The uploader's roster id (see [peerIdFor]); null if its dashboard didn't say. */
    val fromId: String? = null,
)

/** Broadcast when someone uploads a file, so every dashboard updates live. */
@Serializable
@SerialName("file")
data class FileShared(val file: FileMeta) : ServerMessage

/** Broadcast when someone clears the chat, so every dashboard wipes its log. */
@Serializable
@SerialName("chatCleared")
data class ChatCleared(val byName: String) : ServerMessage

/** Broadcast when a single shared file is deleted. */
@Serializable
@SerialName("fileRemoved")
data class FileRemoved(val id: String) : ServerMessage

/** Broadcast when several shared files go at once (a folder deleted, or auto-clear). */
@Serializable
@SerialName("filesRemoved")
data class FilesRemoved(val ids: List<String>) : ServerMessage

/** Broadcast when all shared files are deleted. */
@Serializable
@SerialName("filesCleared")
data class FilesCleared(val byName: String) : ServerMessage

/** A live stream, as listed to every dashboard. */
@Serializable
data class LiveStream(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    /** "camera", "screen" or "audio". */
    val kind: String,
    val viewers: Int,
)

/** Broadcast whenever a stream starts, ends or gains/loses a viewer. */
@Serializable
@SerialName("liveList")
data class LiveList(val streams: List<LiveStream>, val maxViewers: Int) : ServerMessage

/** To a stream's owner: [viewerId] started ([joined]) or stopped watching. */
@Serializable
@SerialName("liveViewer")
data class LiveViewer(val stream: String, val viewerId: String, val joined: Boolean) : ServerMessage

/** To a would-be viewer: stream [stream] already has the maximum number of viewers. */
@Serializable
@SerialName("liveFull")
data class LiveFull(val stream: String) : ServerMessage

/** What is playing for everyone; [ageMs] = how long ago the host reported this state. */
@Serializable
data class MediaInfo(
    /** The shared file being played, or null for a link. */
    val fileId: String?,
    /** What to play: "/files/{id}", a direct https URL, or (kind "youtube") a YouTube video id. */
    val src: String,
    val name: String,
    /** "video", "audio" or "youtube". */
    val kind: String,
    val ownerId: String,
    val playing: Boolean,
    val position: Double,
    val sync: Boolean,
    val ageMs: Long,
)

/** Broadcast whenever playback starts, changes or ends ([session] null). */
@Serializable
@SerialName("mediaSession")
data class MediaSession(val session: MediaInfo?) : ServerMessage

/** A relayed WebRTC setup message from peer [from]. */
@Serializable
@SerialName("signal")
data class SignalEvent(val from: String, val stream: String, val data: JsonElement) : ServerMessage
