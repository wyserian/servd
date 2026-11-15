package dev.servd.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTest {

    private val start = Playback("f1", "/files/f1", "movie.mp4", "video", ownerId = "host", updatedAt = 1_000)

    @Test
    fun a_late_joiner_lands_where_playback_is_now() {
        val playing = start.update(playing = true, position = 30.0, sync = true, now = 10_000)
        assertEquals(30.0, playing.positionAt(10_000))
        assertEquals(42.5, playing.positionAt(22_500), "12.5 s later")
        val info = playing.info(now = 22_500)
        assertEquals(12_500, info.ageMs)
        assertEquals(42.5, info.position + info.ageMs / 1000.0, "what a dashboard computes from the message")
    }

    @Test
    fun paused_playback_stays_put() {
        val paused = start.update(playing = false, position = 61.0, sync = true, now = 5_000)
        assertEquals(61.0, paused.positionAt(99_000))
    }

    @Test
    fun updates_keep_the_file_and_clamp_bad_positions() {
        val p = start.update(playing = true, position = -3.0, sync = false, now = 2_000)
        assertEquals(0.0, p.position)
        assertEquals(false, p.sync)
        assertEquals("f1" to "host", p.fileId to p.ownerId)
        assertEquals(0, p.info(now = 1_500).ageMs, "never negative, even if clocks step back")
    }
}
