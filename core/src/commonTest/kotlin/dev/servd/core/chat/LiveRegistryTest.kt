package dev.servd.core.chat

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveRegistryTest {

    private fun registry(max: Int = 10): LiveRegistry {
        var n = 0
        return LiveRegistry(newId = { "s${++n}" }, maxViewers = max)
    }

    private fun LiveRegistry.idOf(owner: String) = snapshot { it }.first { it.ownerId == owner }.id

    @Test
    fun one_stream_per_owner_and_only_known_kinds() {
        val r = registry()
        assertTrue(r.start("alice", "camera"))
        assertFalse(r.start("alice", "screen"), "already live")
        assertFalse(r.start("bob", "hologram"))
        assertTrue(r.start("bob", "audio"))
        assertEquals(listOf("alice" to "camera", "bob" to "audio"), r.snapshot { it }.map { it.ownerId to it.kind })
    }

    @Test
    fun viewers_are_capped_and_can_rejoin() {
        val r = registry(max = 2)
        r.start("alice", "camera")
        val id = r.idOf("alice")
        assertEquals(LiveRegistry.Watch.Ok("alice"), r.watch(id, "v1"))
        assertEquals(LiveRegistry.Watch.Ok("alice"), r.watch(id, "v2"))
        assertEquals(LiveRegistry.Watch.Full, r.watch(id, "v3"))
        assertEquals(LiveRegistry.Watch.Ok("alice"), r.watch(id, "v1"), "already watching: no extra seat")
        assertEquals("alice", r.leave(id, "v2"))
        assertEquals(LiveRegistry.Watch.Ok("alice"), r.watch(id, "v3"))
        assertEquals(2, r.snapshot { it }.single().viewers)
    }

    @Test
    fun invalid_watches_are_refused() {
        val r = registry()
        r.start("alice", "camera")
        assertIs<LiveRegistry.Watch.Invalid>(r.watch("nope", "v1"))
        assertIs<LiveRegistry.Watch.Invalid>(r.watch(r.idOf("alice"), "alice"), "can't watch yourself")
    }

    @Test
    fun setup_messages_only_flow_between_owner_and_its_viewers() {
        val r = registry()
        r.start("alice", "camera")
        val id = r.idOf("alice")
        r.watch(id, "v1")
        assertTrue(r.canRelay(id, "alice", "v1"))
        assertTrue(r.canRelay(id, "v1", "alice"))
        assertFalse(r.canRelay(id, "alice", "stranger"))
        assertFalse(r.canRelay(id, "stranger", "alice"))
        assertFalse(r.canRelay(id, "v1", "v2"), "viewers can't message each other through it")
        assertFalse(r.canRelay("other", "alice", "v1"))
    }

    @Test
    fun disconnects_end_streams_and_leave_audiences() {
        val r = registry()
        r.start("alice", "camera"); r.start("bob", "screen")
        val a = r.idOf("alice"); val b = r.idOf("bob")
        r.watch(a, "carol"); r.watch(b, "carol")

        val carolGone = r.removePeer("carol")
        assertFalse(carolGone.endedStream)
        assertEquals(setOf(a to "alice", b to "bob"), carolGone.leftFrom.toSet())

        val aliceGone = r.removePeer("alice")
        assertTrue(aliceGone.endedStream)
        assertEquals(listOf("bob"), r.snapshot { it }.map { it.ownerId })
        assertFalse(r.canRelay(a, "alice", "carol"))
        assertFalse(r.removePeer("nobody").changed)
    }

    @Test
    fun signal_messages_round_trip_opaque_json() {
        val json = Json { classDiscriminator = "type"; ignoreUnknownKeys = true }
        val m = json.decodeFromString<ClientMessage>(
            """{"type":"signal","to":"v1","stream":"s1","data":{"sdp":{"type":"offer","sdp":"v=0"}}}""",
        )
        assertIs<SignalSend>(m)
        assertEquals("""{"sdp":{"type":"offer","sdp":"v=0"}}""", m.data.toString())
    }
}
