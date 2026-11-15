package dev.servd.core.files

import java.net.URI

/**
 * A link the host plays for everyone instead of a shared file: a YouTube video, or a direct https
 * link to a video/audio file. Only https is accepted (the dashboard is https, and browsers block
 * plain-http media on it). For YouTube only the video id is kept; dashboards build the embed URL
 * themselves, so a crafted link can't put arbitrary content in everyone's page.
 */
data class MediaLink(
    /** "youtube", "video" or "audio". */
    val kind: String,
    /** The YouTube video id, or the normalized https URL. */
    val src: String,
    val name: String,
) {
    companion object {
        private val YT_ID = Regex("[A-Za-z0-9_-]{11}")

        fun parse(raw: String): MediaLink? {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 2000) return null
            val uri = runCatching { URI(s) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            val host = uri.host.lowercase().removePrefix("www.").removePrefix("m.")
            if (host == "youtu.be" || host == "youtube.com" || host == "music.youtube.com" || host == "youtube-nocookie.com") {
                return youtubeId(host, uri)?.let { MediaLink("youtube", it, "YouTube video") }
            }
            val file = uri.rawPath.orEmpty().substringAfterLast('/')
            // A link without a known audio extension plays in a video player (which also plays audio).
            val kind = if (MediaTypes.forFile(file, null).startsWith("audio/")) "audio" else "video"
            val name = runCatching { java.net.URLDecoder.decode(file, Charsets.UTF_8) }.getOrDefault(file)
            return MediaLink(kind, uri.toASCIIString(), name.ifBlank { host }.take(120))
        }

        private fun youtubeId(host: String, uri: URI): String? {
            val segs = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            val id = when {
                host == "youtu.be" -> segs.firstOrNull()
                segs.firstOrNull() == "watch" -> uri.rawQuery.orEmpty().split('&')
                    .map { it.split('=', limit = 2) }.firstOrNull { it[0] == "v" }?.getOrNull(1)
                segs.size >= 2 && segs[0] in setOf("shorts", "embed", "live", "v") -> segs[1]
                else -> null
            }
            return id?.takeIf { YT_ID.matches(it) }
        }
    }
}
