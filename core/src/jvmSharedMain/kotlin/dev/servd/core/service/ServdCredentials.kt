package dev.servd.core.service

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Secrets generated once on first run and persisted, so they stay stable across restarts:
 * - the single SSH/FTP password (shown in the host-only admin panel). Not a high-value secret - it
 *   guards file access on a trusted LAN, not the open internet.
 * - the admin token: the host's dashboard gets it when the app or CLI opens it, and every admin
 *   request must carry it. Loopback alone isn't enough: any website open in the host's browser,
 *   and any other app on a phone, also connects from 127.0.0.1.
 */
object ServdCredentials {
    const val USERNAME = "servd"
    private val random = SecureRandom()

    /** Load the persisted service password, generating one on first run. */
    fun loadOrCreatePassword(dir: File): String = loadOrCreate(File(dir, "servd.password")) {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789" // no easily-confused chars
        buildString { repeat(14) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    /** Load the persisted admin token (base64url, URL-safe), generating one on first run. */
    fun loadOrCreateAdminToken(dir: File): String = loadOrCreate(File(dir, "admin.token")) {
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    }

    private fun loadOrCreate(file: File, generate: () -> String): String {
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        val value = generate()
        runCatching { file.parentFile?.mkdirs(); file.writeText(value) }
        return value
    }
}
