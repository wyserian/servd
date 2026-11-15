package dev.servd.core.server

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccessTest {

    @Test
    fun the_admin_api_needs_the_host_not_just_this_machine() {
        // loopback without the admin token: any website in the host's browser, any app on the phone
        assertEquals(HttpStatusCode.Forbidden, accessDenied("/admin/pin", loopback = true, session = true, host = false))
        assertEquals(HttpStatusCode.Forbidden, accessDenied("/admin", loopback = false, session = true, host = false))
        assertNull(accessDenied("/admin/browse", loopback = true, session = true, host = true))
        assertNull(accessDenied("/administrator", loopback = false, session = true, host = false), "only /admin and below")
    }

    @Test
    fun with_a_pin_set_a_guest_without_a_session_reaches_only_the_page_and_the_check() {
        for (path in listOf("/", "/auth")) assertNull(accessDenied(path, loopback = false, session = false, host = false), path)
        for (path in listOf("/ws", "/files", "/files/abc", "/fs/list", "/status", "/qr")) {
            assertEquals(HttpStatusCode.Unauthorized, accessDenied(path, loopback = false, session = false, host = false), path)
        }
        assertNull(accessDenied("/files", loopback = false, session = true, host = false), "with a session")
        assertNull(accessDenied("/files", loopback = true, session = false, host = false), "this machine never needs the PIN")
    }
}
