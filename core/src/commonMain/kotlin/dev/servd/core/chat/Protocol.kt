package dev.servd.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class Hello(val name: String) : ClientMessage

@Serializable
@SerialName("chat")
data class ChatSend(val text: String) : ClientMessage

@Serializable
@SerialName("clearChat")
data object ClearChat : ClientMessage

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("welcome")
data class Welcome(val selfId: String, val serverName: String) : ServerMessage

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
