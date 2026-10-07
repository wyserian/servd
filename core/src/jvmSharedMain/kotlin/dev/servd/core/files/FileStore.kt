package dev.servd.core.files

import dev.servd.core.chat.FileMeta
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores files shared to the hub. Bytes live on disk (one file per id, named by id so the
 * original name is never trusted for the filesystem); metadata is kept in memory. Not durable
 * across restarts by design - servd is a transient LAN hub, not a file server.
 */
class FileStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private val metas = ConcurrentHashMap<String, FileMeta>()

    /** Stream [input] to disk and record it. Returns the stored metadata. */
    fun save(originalName: String, contentType: String?, fromName: String, input: InputStream): FileMeta {
        val id = UUID.randomUUID().toString()
        val target = File(dir, id)
        target.outputStream().use { input.copyTo(it) }
        val meta = FileMeta(
            id = id,
            name = sanitizeName(originalName),
            size = target.length(),
            contentType = contentType,
            fromName = fromName,
            ts = System.currentTimeMillis(),
        )
        metas[id] = meta
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

    /** Delete one file (bytes + metadata). Returns its metadata, or null if unknown. */
    fun remove(id: String): FileMeta? {
        val meta = metas.remove(id) ?: return null
        runCatching { File(dir, id).delete() }
        return meta
    }

    /** Delete every shared file. */
    fun clear() {
        metas.keys.toList().forEach { id -> runCatching { File(dir, id).delete() } }
        metas.clear()
    }

    /** Strip any path components so a crafted upload name can't escape the display. */
    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }.take(120)
}
