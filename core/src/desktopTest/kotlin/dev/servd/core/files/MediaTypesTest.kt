package dev.servd.core.files

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTypesTest {

    @Test
    fun previewable_files_get_their_media_type_by_extension() {
        assertEquals("image/jpeg", MediaTypes.forFile("IMG_001.JPG", null))
        assertEquals("image/png", MediaTypes.forFile("shot.png", "application/octet-stream"))
        assertEquals("video/mp4", MediaTypes.forFile("clip.mov", "video/quicktime"))
        assertEquals("audio/mpeg", MediaTypes.forFile("song.mp3", null))
        assertEquals("text/plain; charset=utf-8", MediaTypes.forFile("notes.md", "text/markdown"))
    }

    @Test
    fun anything_that_could_run_script_is_served_as_binary_or_plain_text() {
        // Never rendered as markup, whatever the uploader claims.
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("logo.svg", "image/svg+xml"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("evil", "text/html"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("evil.xhtml", "application/xhtml+xml"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("evil.pdf", "text/html"))
        // Markup files preview as their source text.
        assertEquals("text/plain; charset=utf-8", MediaTypes.forFile("page.html", "text/html"))
        // An SVG claiming to be a PNG is served as PNG: decoded as a raster image or not at all,
        // never as SVG (and with nosniff the browser won't second-guess it).
        assertEquals("image/png", MediaTypes.forFile("x.svg", "image/png"))
    }

    @Test
    fun unknown_extensions_fall_back_to_a_safe_claimed_type_only() {
        assertEquals("image/webp", MediaTypes.forFile("photo", "image/webp"))
        assertEquals("video/mp4", MediaTypes.forFile("recording", "video/quicktime"))
        assertEquals("text/plain; charset=utf-8", MediaTypes.forFile("README", "text/plain"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("setup.exe", "application/x-msdownload"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("photo.heic", "image/heic"))
        assertEquals(MediaTypes.BINARY, MediaTypes.forFile("data.bin", null))
    }
}
