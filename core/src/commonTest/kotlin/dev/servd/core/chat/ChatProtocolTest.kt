package dev.servd.core.chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerRegistryTest {
    @Test
    fun join_reports_new_then_updates_in_place() {
        val r = PeerRegistry()
        assertTrue(r.join(Peer("1", "alice", "10.0.0.2"), "c1"))
        assertTrue(!r.join(Peer("1", "alice-renamed", "10.0.0.2"), "c1")) // same id -> not new
        assertEquals(1, r.size)
        assertEquals("alice-renamed", r.get("1")?.name)
    }

    @Test
    fun a_device_with_two_tabs_leaves_only_when_the_last_closes() {
        val r = PeerRegistry()
        assertTrue(r.join(Peer("dev", "bob", "x"), "tab1"))
        assertTrue(!r.join(Peer("dev", "bob", "x"), "tab2"), "second tab: same device, no new join")
        assertNull(r.leave("dev", "tab1"), "one tab still open")
        assertTrue(r.contains("dev"))
        assertEquals("bob", r.leave("dev", "tab2")?.name)
        assertTrue(!r.contains("dev"))
        assertNull(r.leave("dev", "tab2"), "already gone")
    }

    @Test
    fun leave_and_snapshot_preserve_join_order() {
        val r = PeerRegistry()
        r.join(Peer("1", "a", "x"), "c1"); r.join(Peer("2", "b", "y"), "c2"); r.join(Peer("3", "c", "z"), "c3")
        assertEquals(listOf("a", "b", "c"), r.snapshot().map { it.name })
        assertEquals("b", r.leave("2", "c2")?.name)
        assertEquals(listOf("a", "c"), r.snapshot().map { it.name })
    }
}

class ProtocolTest {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun decodes_client_messages_by_type() {
        val hello = json.decodeFromString<ClientMessage>("""{"type":"hello","name":"pixel-7"}""")
        assertTrue(hello is Hello && hello.name == "pixel-7")
        val chat = json.decodeFromString<ClientMessage>("""{"type":"chat","text":"hi"}""")
        assertTrue(chat is ChatSend && chat.text == "hi")
    }

    @Test
    fun encodes_server_messages_with_type_discriminator() {
        val chat = json.encodeToString<ServerMessage>(ChatEvent("1", "alice", "hi", 123L))
        assertTrue(chat.contains("\"type\":\"chat\""), chat)
        assertTrue(chat.contains("\"fromName\":\"alice\""), chat)

        val roster = json.encodeToString<ServerMessage>(Roster(listOf(Peer("1", "a", "x"))))
        assertTrue(roster.contains("\"type\":\"roster\""), roster)

        val presence = json.encodeToString<ServerMessage>(PresenceEvent("join", Peer("1", "a", "x")))
        assertTrue(presence.contains("\"type\":\"presence\""), presence)
    }
}
