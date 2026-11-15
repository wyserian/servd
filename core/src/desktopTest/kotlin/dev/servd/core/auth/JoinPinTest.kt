package dev.servd.core.auth

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinPinTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("servd-pin").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun off_by_default_and_everyone_is_allowed() {
        val pin = JoinPin(null)
        assertFalse(pin.enabled)
        assertTrue(pin.allows(null))
    }

    @Test
    fun only_4_to_8_digits_are_valid() {
        for (ok in listOf("1234", "00000000", "482913")) assertTrue(JoinPin.isValid(ok), ok)
        for (bad in listOf("123", "123456789", "12a4", "12 34", "", "١٢٣٤")) assertFalse(JoinPin.isValid(bad), bad)
        assertFailsWith<IllegalArgumentException> { JoinPin(null).set("12") }
    }

    @Test
    fun correct_pin_gives_a_session_and_wrong_pin_does_not() {
        val pin = JoinPin(null).apply { set("4821") }
        assertFalse(pin.allows(null))
        assertFalse(pin.allows("made-up"))
        assertIs<JoinPin.Attempt.Wrong>(pin.attempt("10.0.0.5", "1111"))
        val ok = assertIs<JoinPin.Attempt.Ok>(pin.attempt("10.0.0.5", "4821"))
        assertTrue(pin.allows(ok.token))
    }

    @Test
    fun persists_across_restarts_until_turned_off() = withDir { dir ->
        val file = File(dir, "join.pin")
        JoinPin(file).set("4821")
        assertEquals("4821", JoinPin(file).current)
        JoinPin(file).set(null)
        assertFalse(file.exists())
        assertNull(JoinPin(file).current)
    }

    @Test
    fun a_corrupt_saved_pin_is_ignored() = withDir { dir ->
        val file = File(dir, "join.pin").apply { writeText("not-a-pin") }
        assertFalse(JoinPin(file).enabled)
    }

    @Test
    fun changing_or_clearing_the_pin_signs_everyone_out() {
        val pin = JoinPin(null).apply { set("4821") }
        val token = (pin.attempt("10.0.0.5", "4821") as JoinPin.Attempt.Ok).token
        pin.set("9999")
        assertFalse(pin.allows(token))
        val token2 = (pin.attempt("10.0.0.5", "9999") as JoinPin.Attempt.Ok).token
        pin.set(null)
        pin.set("1234")
        assertFalse(pin.allows(token2))
    }

    @Test
    fun repeated_misses_lock_the_address_out_with_doubling_waits() {
        var clock = 1_000_000L
        val pin = JoinPin(null) { clock }.apply { set("4821") }
        val ip = "10.0.0.5"

        repeat(JoinPin.FREE_TRIES - 1) { i ->
            assertEquals(JoinPin.Attempt.Wrong(JoinPin.FREE_TRIES - 1 - i), pin.attempt(ip, "0000"))
        }
        assertEquals(JoinPin.Attempt.Locked(30_000), pin.attempt(ip, "0000"))
        // Even the right PIN is refused while locked, and another address is unaffected.
        assertIs<JoinPin.Attempt.Locked>(pin.attempt(ip, "4821"))
        assertIs<JoinPin.Attempt.Ok>(pin.attempt("10.0.0.6", "4821"))

        clock += 30_000
        assertEquals(JoinPin.Attempt.Locked(60_000), pin.attempt(ip, "0000"))
        clock += 60_000
        assertIs<JoinPin.Attempt.Ok>(pin.attempt(ip, "4821"))
        // A success resets the count.
        assertEquals(JoinPin.Attempt.Wrong(JoinPin.FREE_TRIES - 1), pin.attempt(ip, "0000"))
    }

    @Test
    fun lockout_is_capped() {
        var clock = 0L
        val pin = JoinPin(null) { clock }.apply { set("4821") }
        var last: JoinPin.Attempt? = null
        repeat(40) {
            val r = pin.attempt("10.0.0.5", "0000")
            if (r is JoinPin.Attempt.Locked) clock += r.retryAfterMs
            last = r
        }
        assertEquals(JoinPin.Attempt.Locked(JoinPin.MAX_LOCK_MS), last)
    }
}
