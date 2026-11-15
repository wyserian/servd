package dev.servd.core.chat

/**
 * What the host is playing for everyone, as last reported by the host's player: a shared file
 * ([fileId] set) or a link. Pure state: the hub stamps [updatedAt] and sends each device how old
 * the report is ([MediaInfo.ageMs]); while playing, a device adds that to [position], so no clocks
 * need to agree.
 */
data class Playback(
    /** The shared file being played, or null for a link. */
    val fileId: String?,
    /** What devices play: "/files/{id}", a direct https URL, or a YouTube video id. */
    val src: String,
    val name: String,
    /** "video", "audio" or "youtube". */
    val kind: String,
    val ownerId: String,
    val updatedAt: Long,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val sync: Boolean = true,
) {
    fun update(playing: Boolean, position: Double, sync: Boolean, now: Long) =
        copy(playing = playing, position = position.coerceAtLeast(0.0), sync = sync, updatedAt = now)

    /** As sent to dashboards at [now]. */
    fun info(now: Long) = MediaInfo(fileId, src, name, kind, ownerId, playing, position, sync, (now - updatedAt).coerceAtLeast(0))
}
