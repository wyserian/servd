package dev.servd.core.files

import dev.servd.core.chat.FileMeta
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Stores files shared to the hub. Bytes live on disk (one file per id, named by id so the
 * original name is never trusted for the filesystem); metadata is kept in memory. Not durable
 * across restarts by design - servd is a transient LAN hub, not a file server - so the previous
 * run's files are deleted on start (nothing could list or remove them otherwise).
 *
 * Uploads are bounded while they stream: by the host's optional quota ([DropLimits.maxBytes]) and,
 * always, by a free-space floor that keeps the host's disk from filling up ([freeFloor]). A file
 * that crosses either is aborted and its partial bytes deleted ([StorageFullException]).
 */
class FileStore(
    private val dir: File,
    val limits: DropLimits = DropLimits(null),
    private val usableSpace: () -> Long = { dir.usableSpace },
    private val totalSpace: () -> Long = { dir.totalSpace },
) {

    init {
        dir.mkdirs()
        // Only our own id-named files: SFTP/FTP uploads share this folder under their real names.
        dir.listFiles()?.filter { it.isFile && ID_NAME.matches(it.name) }?.forEach { it.delete() }
    }

    private val metas = ConcurrentHashMap<String, FileMeta>()
    private val stored = AtomicLong()   // bytes of the listed files
    private val inFlight = AtomicLong() // bytes written so far by uploads still streaming

    @Serializable
    data class Usage(
        /** Bytes taken by shared files. */
        val usedBytes: Long,
        /** The host's quota, or null for none. */
        val maxBytes: Long?,
        /** How much more can be uploaded right now (quota and free-space floor). */
        val roomBytes: Long,
        /** Auto-clear age in hours, or null when files never expire. */
        val expireHours: Int?,
    )

    class StorageFullException(val usage: Usage) : RuntimeException("hub storage is full")

    fun usage(): Usage = Usage(stored.get(), limits.maxBytes, room(), limits.expireHours)

    /** Disk space always left free: 1 GB or 5% of the disk, whichever is larger. */
    fun freeFloor(): Long = maxOf(MIN_FREE_BYTES, totalSpace() / 20)

    /** Bytes uploads may still add: the quota's remainder, capped by the disk's room above the floor. */
    fun room(): Long {
        val disk = usableSpace() - freeFloor() // in-flight bytes are already on disk
        val quota = limits.maxBytes?.let { it - stored.get() - inFlight.get() } ?: Long.MAX_VALUE
        return minOf(disk, quota).coerceAtLeast(0)
    }

    /**
     * Stream [input] to disk and record it. Returns the stored metadata. For a file from a dropped
     * folder, [path] is its path inside that folder and [batch] groups the drop; both are
     * sanitized, and dropped (the file is stored loose) if invalid.
     */
    fun save(
        originalName: String,
        contentType: String?,
        fromName: String,
        input: InputStream,
        path: String? = null,
        batch: String? = null,
    ): FileMeta {
        if (room() <= 0) throw StorageFullException(usage())
        val id = UUID.randomUUID().toString()
        val target = File(dir, id)
        var written = 0L
        try {
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var sinceDiskCheck = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    inFlight.addAndGet(n.toLong())
                    sinceDiskCheck += n
                    val quota = limits.maxBytes
                    if (quota != null && stored.get() + inFlight.get() > quota) throw StorageFullException(usage())
                    if (sinceDiskCheck >= DISK_CHECK_BYTES) {
                        sinceDiskCheck = 0
                        if (usableSpace() < freeFloor()) throw StorageFullException(usage())
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete() // never leave a partial upload behind
            inFlight.addAndGet(-written)
            written = 0
            // Report the room as it is now that the partial bytes are gone, not mid-upload.
            throw if (e is StorageFullException) StorageFullException(usage()) else e
        } finally {
            inFlight.addAndGet(-written)
        }
        val meta = FileMeta(
            id = id,
            name = sanitizeName(originalName),
            size = target.length(),
            contentType = contentType,
            fromName = fromName,
            ts = System.currentTimeMillis(),
        ).let { m ->
            val cleanBatch = batch?.takeIf { BATCH_ID.matches(it) }
            val cleanPath = cleanBatch?.let { sanitizePath(path) }
            if (cleanPath != null) m.copy(path = cleanPath, batch = cleanBatch) else m
        }
        metas[id] = meta
        stored.addAndGet(meta.size)
        return meta
    }

    /** Newest first. */
    fun list(): List<FileMeta> = metas.values.sortedByDescending { it.ts }

    /** Metadata + the backing file, or null if unknown. */
    fun get(id: String): Pair<FileMeta, File>? {
        val meta = metas[id] ?: return null
        val file = File(dir, id)
        if (!file.exists()) return null
        return meta to file
    }

    /** Every stored file (newest first) with its backing file - for "download all". */
    fun entries(): List<Pair<FileMeta, File>> = list().mapNotNull { get(it.id) }

    /** The files of one folder drop, with their backing files. */
    fun batchEntries(batch: String): List<Pair<FileMeta, File>> = entries().filter { it.first.batch == batch }

    /** Delete every file of one folder drop. Returns what was removed. */
    fun removeBatch(batch: String): List<FileMeta> =
        metas.values.filter { it.batch == batch }.mapNotNull { remove(it.id) }

    /** Delete one file (bytes + metadata). Returns its metadata, or null if unknown. */
    fun remove(id: String): FileMeta? {
        val meta = metas.remove(id) ?: return null
        stored.addAndGet(-meta.size)
        runCatching { File(dir, id).delete() }
        return meta
    }

    /** Delete every shared file. */
    fun clear() {
        metas.keys.toList().forEach { remove(it) }
    }

    /** Auto-clear: delete files older than [DropLimits.expireHours]. Returns what was removed. */
    fun expire(now: Long = System.currentTimeMillis()): List<FileMeta> {
        val hours = limits.expireHours ?: return emptyList()
        val cutoff = now - hours * 3_600_000L
        return metas.values.filter { it.ts < cutoff }.mapNotNull { remove(it.id) }
    }

    /** Strip any path components so a crafted upload name can't escape the display. */
    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }.take(120)

    companion object {
        /**
         * A folder-relative path made safe for display and for zip entries: "/" separators only,
         * no empty, "." or ".." segments (so nothing can climb out of an extracted zip), no
         * characters Windows rejects. Null unless it is at least "folder/file".
         */
        fun sanitizePath(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val parts = path.replace('\\', '/').split('/')
                .map { seg -> seg.trim().replace(UNSAFE_CHARS, "_").take(120) }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
            if (parts.size < 2) return null
            return parts.joinToString("/").take(1000)
        }

        private val UNSAFE_CHARS = Regex("""[\u0000-\u001f:*?"<>|]""")
        private val BATCH_ID = Regex("[A-Za-z0-9-]{8,64}")
        const val MIN_FREE_BYTES = 1L shl 30 // 1 GB
        private const val DISK_CHECK_BYTES = 8L shl 20 // re-check free space every 8 MB written
        private val ID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
