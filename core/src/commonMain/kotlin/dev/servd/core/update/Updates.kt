package dev.servd.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer servd release the Android app can install. */
data class Update(val version: String, val apkUrl: String, val size: Long)

/** Reads GitHub's "latest release" answer and decides whether it is newer than this build. */
object Updates {
    const val LATEST_URL = "https://api.github.com/repos/wyserian/servd/releases/latest"

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Asset> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** The release's APK if it is newer than [current]; null if not, or if the answer is unusable. */
    fun newerApk(releaseJson: String, current: String): Update? {
        val release = runCatching { json.decodeFromString<Release>(releaseJson) }.getOrNull() ?: return null
        val version = release.tag.removePrefix("v")
        if (!isNewer(version, current)) return null
        val apk = release.assets.firstOrNull { it.name.endsWith("-release.apk") && it.url.startsWith("https://") }
            ?: return null
        return Update(version, apk.url, apk.size)
    }

    /** Plain x.y.z comparison; anything else (e.g. "1.8.0-rc1") is never treated as newer. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until 3) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    private fun parts(v: String): List<Int>? =
        v.split(".").takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null }
}
