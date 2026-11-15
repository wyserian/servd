package dev.servd.core.chat

/**
 * What the host is playing for everyone, as last reported by the host's player: a shared file
 * ([fileId] set) or a link. Pure state: the hub stamps [updatedAt]; devices work out the current
 * position from it (see [positionAt]), so no clocks need to agree.
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

    /** Where playback should be at [now]: moving on from the last report while playing. */
    fun positionAt(now: Long): Double = if (playing) position + (now - updatedAt) / 1000.0 else position

    /** As sent to dashboards; they add [MediaInfo.ageMs] the same way [positionAt] does. */
    fun info(now: Long) = MediaInfo(fileId, src, name, kind, ownerId, playing, position, sync, (now - updatedAt).coerceAtLeast(0))
}
