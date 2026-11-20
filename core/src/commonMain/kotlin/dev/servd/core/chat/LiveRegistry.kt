package dev.servd.core.chat

/**
 * Who is live and who is watching. Pure bookkeeping (no I/O), driven by [ChatHub]: one stream per
 * connection, at most [maxViewers] viewers each. It also decides which WebRTC setup messages may be
 * relayed: only between a stream's owner and that stream's current viewers. Not thread-safe; the
 * hub calls it under a lock.
 */
class LiveRegistry(private val newId: () -> String, val maxViewers: Int = MAX_VIEWERS) {

    private class Entry(val id: String, val ownerId: String, val kind: String) {
        val viewers = LinkedHashSet<String>()
    }

    private val byOwner = LinkedHashMap<String, Entry>() // start order

    sealed interface Watch {
        /** [added] is false when the viewer was already watching (a re-connect): the count didn't change. */
        data class Ok(val ownerId: String, val added: Boolean = true) : Watch
        data object Full : Watch
        data object Invalid : Watch
    }

    /** What changed when a peer went away. */
    data class Removal(val endedStream: Boolean, val leftFrom: List<Pair<String, String>>) {
        val changed get() = endedStream || leftFrom.isNotEmpty()
    }

    /** Start [ownerId]'s stream. False if [kind] is unknown or they are already live. */
    fun start(ownerId: String, kind: String): Boolean {
        if (kind !in KINDS || ownerId in byOwner) return false
        byOwner[ownerId] = Entry(newId(), ownerId, kind)
        return true
    }

    /** End [ownerId]'s stream. False if they weren't live. */
    fun stop(ownerId: String): Boolean = byOwner.remove(ownerId) != null

    fun watch(streamId: String, viewerId: String): Watch {
        val e = find(streamId) ?: return Watch.Invalid
        if (e.ownerId == viewerId) return Watch.Invalid
        if (viewerId in e.viewers) return Watch.Ok(e.ownerId, added = false)
        if (e.viewers.size >= maxViewers) return Watch.Full
        e.viewers += viewerId
        return Watch.Ok(e.ownerId)
    }

    /** Stop watching; returns the stream's owner if [viewerId] was watching it. */
    fun leave(streamId: String, viewerId: String): String? {
        val e = find(streamId) ?: return null
        return if (e.viewers.remove(viewerId)) e.ownerId else null
    }

    /** Setup messages only flow between a stream's owner and its viewers, in either direction. */
    fun canRelay(streamId: String, from: String, to: String): Boolean {
        val e = find(streamId) ?: return false
        return (from == e.ownerId && to in e.viewers) || (to == e.ownerId && from in e.viewers)
    }

    /** A connection closed: end its stream and take it out of any audience. */
    fun removePeer(peerId: String): Removal {
        val ended = byOwner.remove(peerId) != null
        val left = byOwner.values.filter { it.viewers.remove(peerId) }.map { it.id to it.ownerId }
        return Removal(ended, left)
    }

    fun snapshot(nameOf: (String) -> String?): List<LiveStream> =
        byOwner.values.map { LiveStream(it.id, it.ownerId, nameOf(it.ownerId) ?: "someone", it.kind, it.viewers.size) }

    private fun find(streamId: String) = byOwner.values.firstOrNull { it.id == streamId }

    companion object {
        const val MAX_VIEWERS = 10
        val KINDS = setOf("camera", "screen", "audio", "sound") // sound: what the host computer is playing
    }
}
