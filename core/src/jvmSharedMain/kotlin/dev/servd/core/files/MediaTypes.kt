package dev.servd.core.files

/**
 * The media type a shared file is served with. Only types the dashboard previews - raster images,
 * video, audio and plain text - get a real type; everything else is application/octet-stream.
 * The uploader's claimed type is never trusted as-is: SVG, HTML and other types that could run
 * script are never returned. Files are still sent as attachments, and with nosniff, so opening
 * one directly downloads it rather than rendering it on the hub's origin.
 */
object MediaTypes {
    const val BINARY = "application/octet-stream"

    private val IMAGE = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "avif" to "image/avif", "bmp" to "image/bmp",
    )
    private val VIDEO = mapOf(
        // .mov is usually H.264 inside; browsers play it when labelled mp4.
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/mp4", "webm" to "video/webm",
    )
    private val AUDIO = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav", "flac" to "audio/flac",
    )
    /** Shown as plain text, never rendered - including markup and code files. */
    private val TEXT = setOf(
        "txt", "md", "log", "json", "csv", "tsv", "xml", "yaml", "yml", "ini", "conf", "cfg", "toml",
        "properties", "env", "sql", "sh", "bat", "ps1", "py", "js", "ts", "kt", "kts", "java", "go", "rs",
        "c", "h", "cpp", "hpp", "cs", "rb", "php", "swift", "html", "htm", "css", "gradle",
    )
    private const val TEXT_TYPE = "text/plain; charset=utf-8"

    /** Safe claimed types (when the name has no known extension). */
    private val SAFE_CLAIMED = (IMAGE.values + VIDEO.values + AUDIO.values + "video/quicktime").toSet()

    fun forFile(name: String, claimed: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        IMAGE[ext]?.let { return it }
        VIDEO[ext]?.let { return it }
        AUDIO[ext]?.let { return it }
        if (ext in TEXT) return TEXT_TYPE
        val c = claimed?.substringBefore(';')?.trim()?.lowercase()
        return when {
            c == null -> BINARY
            c == "video/quicktime" -> "video/mp4"
            c in SAFE_CLAIMED -> c
            c == "text/plain" -> TEXT_TYPE
            else -> BINARY
        }
    }
}
