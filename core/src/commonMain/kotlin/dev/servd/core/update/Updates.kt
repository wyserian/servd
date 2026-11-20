package dev.servd.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer servd release the Android app can install. */
data class Update(val version: String, val apkUrl: String, val size: Long)

/** A newer servd release, and its GitHub page (where every platform's download is). */
data class NewRelease(val version: String, val pageUrl: String)

/** Reads GitHub's "latest release" answer and decides whether it is newer than this build. */
object Updates {
    const val LATEST_URL = "https://api.github.com/repos/wyserian/servd/releases/latest"

    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        @SerialName("html_url") val page: String = "",
        val assets: List<Asset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The release if it is newer than [current]; null if not, or if the answer is unusable. */
    private fun newer(releaseJson: String, current: String): Release? =
        runCatching { json.decodeFromString<Release>(releaseJson) }.getOrNull()
            ?.takeIf { isNewer(it.tag.removePrefix("v"), current) }

    /** The release's version and page if it is newer than [current] (desktop: "update available"). */
    fun newerRelease(releaseJson: String, current: String): NewRelease? =
        newer(releaseJson, current)?.takeIf { it.page.startsWith("https://") }
            ?.let { NewRelease(it.tag.removePrefix("v"), it.page) }

    /** The release's APK if it is newer than [current] (the Android app installs it). */
    fun newerApk(releaseJson: String, current: String): Update? {
        val release = newer(releaseJson, current) ?: return null
        val apk = release.assets.firstOrNull { it.name.endsWith("-release.apk") && it.url.startsWith("https://") }
            ?: return null
        return Update(release.tag.removePrefix("v"), apk.url, apk.size)
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
