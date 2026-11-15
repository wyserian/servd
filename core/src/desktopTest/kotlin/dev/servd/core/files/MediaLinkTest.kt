package dev.servd.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaLinkTest {

    @Test
    fun youtube_links_in_all_their_shapes_become_a_video_id() {
        for (url in listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=abc",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )) assertEquals(MediaLink("youtube", "dQw4w9WgXcQ", "YouTube video"), MediaLink.parse(url), url)
    }

    @Test
    fun youtube_links_without_a_valid_id_are_refused() {
        assertNull(MediaLink.parse("https://www.youtube.com/"))
        assertNull(MediaLink.parse("https://www.youtube.com/watch?v=short"))
        assertNull(MediaLink.parse("https://youtu.be/dQw4w9WgXcQ\"><script>"))
        assertNull(MediaLink.parse("https://www.youtube.com/channel/UC1234567890"))
    }

    @Test
    fun direct_links_play_as_video_or_audio() {
        assertEquals(MediaLink("video", "https://example.com/films/my%20clip.mp4", "my clip.mp4"),
            MediaLink.parse("https://example.com/films/my%20clip.mp4"))
        assertEquals("audio", MediaLink.parse("https://example.com/podcast.mp3?x=1")?.kind)
        assertEquals("video", MediaLink.parse("https://cdn.example.com/stream")?.kind, "no extension: try as video")
    }

    @Test
    fun only_https_links_are_accepted() {
        assertNull(MediaLink.parse("http://example.com/a.mp4"))
        assertNull(MediaLink.parse("javascript:alert(1)"))
        assertNull(MediaLink.parse("file:///C:/movie.mp4"))
        assertNull(MediaLink.parse("data:video/mp4;base64,AAAA"))
        assertNull(MediaLink.parse("example.com/a.mp4"))
        assertNull(MediaLink.parse(""))
        assertNull(MediaLink.parse("https://example.com/" + "a".repeat(2000)))
    }
}
