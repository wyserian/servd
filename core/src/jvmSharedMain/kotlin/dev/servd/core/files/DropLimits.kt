package dev.servd.core.files

import java.io.File
import java.util.Properties

/**
 * The host's drop-files limits, persisted to [file] (null = in memory only):
 * - [maxBytes]: an optional total quota for shared files (null = no quota; the disk's free-space
 *   floor still applies, see [FileStore]).
 * - [expireHours]: optional auto-clear - files older than this are deleted. One of [EXPIRE_CHOICES].
 */
class DropLimits(private val file: File?) {
    @Volatile var maxBytes: Long? = null
        private set
    @Volatile var expireHours: Int? = null
        private set

    init {
        val props = Properties()
        val saved = file
        if (saved != null && runCatching { saved.inputStream().use { props.load(it) } }.isSuccess) {
            maxBytes = props.getProperty("maxBytes")?.toLongOrNull()?.takeIf { it > 0 }
            expireHours = props.getProperty("expireHours")?.toIntOrNull()?.takeIf { it in EXPIRE_CHOICES }
        }
    }

    /** Replace both limits (null = off) and persist them. */
    @Synchronized
    fun set(maxBytes: Long?, expireHours: Int?) {
        require(maxBytes == null || maxBytes > 0) { "quota must be positive" }
        require(expireHours == null || expireHours in EXPIRE_CHOICES) { "auto-clear must be one of $EXPIRE_CHOICES hours" }
        if (file != null) {
            if (maxBytes == null && expireHours == null) file.delete()
            else {
                val props = Properties()
                maxBytes?.let { props.setProperty("maxBytes", it.toString()) }
                expireHours?.let { props.setProperty("expireHours", it.toString()) }
                file.parentFile?.mkdirs()
                file.outputStream().use { props.store(it, "servd drop-files limits") }
            }
        }
        this.maxBytes = maxBytes
        this.expireHours = expireHours
    }

    companion object {
        /** Auto-clear choices, in hours: 1 h, 24 h, 7 days. */
        val EXPIRE_CHOICES = listOf(1, 24, 168)
    }
}
