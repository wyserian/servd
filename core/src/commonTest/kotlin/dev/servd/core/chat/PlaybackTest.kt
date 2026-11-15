package dev.servd.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTest {

    private val start = Playback("f1", "/files/f1", "movie.mp4", "video", ownerId = "host", updatedAt = 1_000)

    /** Where a dashboard puts the player for [info]: the reported spot, plus its age while playing. */
    private fun landsAt(info: MediaInfo) = if (info.playing) info.position + info.ageMs / 1000.0 else info.position

    @Test
    fun a_late_joiner_lands_where_playback_is_now() {
        val playing = start.update(playing = true, position = 30.0, sync = true, now = 10_000)
        assertEquals(30.0, landsAt(playing.info(now = 10_000)))
        val later = playing.info(now = 22_500)
        assertEquals(12_500, later.ageMs)
        assertEquals(42.5, landsAt(later), "12.5 s later")
    }

    @Test
    fun paused_playback_stays_put() {
        val paused = start.update(playing = false, position = 61.0, sync = true, now = 5_000)
        assertEquals(61.0, landsAt(paused.info(now = 99_000)))
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
