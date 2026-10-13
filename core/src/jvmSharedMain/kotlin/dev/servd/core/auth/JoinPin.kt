package dev.servd.core.auth

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * The optional join PIN: when set, devices other than the host must enter it before they can use
 * the hub. Off by default. The PIN (4-8 digits) is persisted to [file] so it survives restarts,
 * until the host turns it off.
 *
 * A correct PIN mints a random session token (sent as a cookie). Sessions live in memory: a
 * restart, or changing / clearing the PIN, signs every device out. Wrong guesses are rate-limited
 * per client address: after [FREE_TRIES] misses, that address is locked out for 30 s, doubling on
 * each further miss (capped at 15 min). A correct PIN resets its count.
 */
class JoinPin(private val file: File?, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile private var pin: String? = file?.let { f -> runCatching { f.readText().trim() }.getOrNull()?.takeIf(::isValid) }
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val misses = ConcurrentHashMap<String, Misses>()

    private class Misses(var count: Int = 0, var lockedUntil: Long = 0)

    val enabled: Boolean get() = pin != null

    /** The current PIN, for the host's own view (loopback admin only). */
    val current: String? get() = pin

    /** Set a new PIN ([newPin] must be 4-8 digits), or turn it off with null. Signs everyone out. */
    @Synchronized
    fun set(newPin: String?) {
        require(newPin == null || isValid(newPin)) { "PIN must be 4-8 digits" }
        if (file != null) {
            if (newPin == null) file.delete()
            else { file.parentFile?.mkdirs(); file.writeText(newPin) }
        }
        pin = newPin
        sessions.clear()
        misses.clear()
    }

    /** Whether a request carrying session [token] may use the hub (always true while off). */
    fun allows(token: String?): Boolean = !enabled || (token != null && token in sessions)

    sealed interface Attempt {
        /** Correct: use [token] as the session cookie. */
        data class Ok(val token: String) : Attempt
        /** Wrong PIN; [triesLeft] before a lockout. */
        data class Wrong(val triesLeft: Int) : Attempt
        /** Too many misses from this address; try again in [retryAfterMs]. */
        data class Locked(val retryAfterMs: Long) : Attempt
    }

    /** Check a PIN entered by the client at [address]. */
    fun attempt(address: String, entered: String): Attempt {
        val expected = pin ?: return Attempt.Ok(newSession())
        val m = misses.getOrPut(address) { Misses() }
        synchronized(m) {
            val t = now()
            if (t < m.lockedUntil) return Attempt.Locked(m.lockedUntil - t)
            if (MessageDigest.isEqual(entered.trim().toByteArray(), expected.toByteArray())) {
                misses.remove(address)
                return Attempt.Ok(newSession())
            }
            m.count++
            if (m.count < FREE_TRIES) return Attempt.Wrong(FREE_TRIES - m.count)
            val lock = minOf(BASE_LOCK_MS shl minOf(m.count - FREE_TRIES, 10), MAX_LOCK_MS)
            m.lockedUntil = t + lock
            return Attempt.Locked(lock)
        }
    }

    private fun newSession(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { sessions += it }
    }

    companion object {
        const val COOKIE = "servd_session"
        const val FREE_TRIES = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L
        private val random = SecureRandom()

        fun isValid(pin: String): Boolean = pin.length in 4..8 && pin.all { it in '0'..'9' }
    }
}
