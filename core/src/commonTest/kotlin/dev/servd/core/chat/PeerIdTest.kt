package dev.servd.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class PeerIdTest {

    @Test
    fun tabs_of_one_browser_share_an_entry_but_not_across_addresses() {
        val dev = "0123456789abcdef01234567"
        assertEquals(peerIdFor("conn-a", dev, "192.168.1.5"), peerIdFor("conn-b", dev, "192.168.1.5"))
        assertEquals("$dev@192.168.1.9", peerIdFor("conn-c", dev, "192.168.1.9"), "same id, other device")
    }

    @Test
    fun a_missing_or_odd_device_id_falls_back_to_the_connection() {
        assertEquals("conn-a", peerIdFor("conn-a", null, "10.0.0.2"))
        assertEquals("conn-a", peerIdFor("conn-a", "short", "10.0.0.2"))
        assertEquals("conn-a", peerIdFor("conn-a", "has@at-sign-and-more", "10.0.0.2"))
    }
}
