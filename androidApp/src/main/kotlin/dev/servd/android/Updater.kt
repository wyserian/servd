package dev.servd.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.servd.core.Servd
import dev.servd.core.update.Update
import dev.servd.core.update.Updates
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub Releases: a daily check for a newer signed APK, then a download
 * straight into Android's installer. Android refuses any APK not signed with this app's key, which
 * is what makes installing a downloaded file safe. Network calls block: call them off the main thread.
 */
object Updater {
    private const val PREFS = "servd-update"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A newer release, from GitHub at most once a day (else the last answer). Null if none or offline. */
    fun check(context: Context): Update? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checkedAt", 0) in 0 until DAY_MS) {
            val v = prefs.getString("version", null) ?: return null
            val url = prefs.getString("url", null) ?: return null
            return Update(v, url, prefs.getLong("size", 0)).takeIf { Updates.isNewer(v, Servd.VERSION) }
        }
        val body = runCatching { get(Updates.LATEST_URL).inputStream.bufferedReader().use { it.readText() } }
            .getOrNull() ?: return null // offline: try again next time
        val update = Updates.newerApk(body, Servd.VERSION)
        prefs.edit().putLong("checkedAt", now)
            .putString("version", update?.version).putString("url", update?.apkUrl).putLong("size", update?.size ?: 0)
            .apply()
        return update
    }

    /** Download [update] into an install session and hand it to the system installer. */
    fun install(context: Context, update: Update, onProgress: (percent: Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                val conn = get(update.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.size
                conn.inputStream.use { input ->
                    session.openWrite("servd.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != shown) { shown = pct; onProgress(pct) }
                        }
                        session.fsync(out)
                    }
                }
                // The installer reports back here (it may need the user to confirm).
                val status = Intent(context, InstallStatusActivity::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getActivity(context, sessionId, status, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            if (url == Updates.LATEST_URL) setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "servd/${Servd.VERSION}")
            if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode")
        }
}
