package dev.servd.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {

    private fun release(tag: String, vararg assets: String) = """
        {"tag_name":"$tag","draft":false,"assets":[${assets.joinToString(",") {
            """{"name":"$it","size":123,"browser_download_url":"https://github.com/wyserian/servd/releases/download/$tag/$it"}"""
        }}]}
    """

    @Test
    fun versions_compare_numerically() {
        assertTrue(Updates.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updates.isNewer("2.0.0", "1.99.99"))
        assertFalse(Updates.isNewer("1.7.0", "1.7.0"))
        assertFalse(Updates.isNewer("1.6.9", "1.7.0"))
        assertFalse(Updates.isNewer("1.8.0-rc1", "1.7.0"))
        assertFalse(Updates.isNewer("1.8", "1.7.0"))
    }

    @Test
    fun picks_the_release_apk_of_a_newer_release() {
        val u = Updates.newerApk(release("v1.8.0", "servd-1.8.0-windows.zip", "servd-1.8.0-release.apk"), "1.7.0")
        assertEquals("1.8.0", u?.version)
        assertEquals("https://github.com/wyserian/servd/releases/download/v1.8.0/servd-1.8.0-release.apk", u?.apkUrl)
        assertEquals(123, u?.size)
    }

    @Test
    fun nothing_when_same_version_no_apk_or_garbage() {
        assertNull(Updates.newerApk(release("v1.7.0", "servd-1.7.0-release.apk"), "1.7.0"))
        assertNull(Updates.newerApk(release("v1.8.0", "servd-1.8.0-windows.zip"), "1.7.0"))
        assertNull(Updates.newerApk("""{"message":"Not Found"}""", "1.7.0"))
        assertNull(Updates.newerApk("not json", "1.7.0"))
    }
}
