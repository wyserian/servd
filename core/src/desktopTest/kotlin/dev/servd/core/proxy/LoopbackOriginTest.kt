package dev.servd.core.proxy

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopbackOriginTest {

    /** A fake probe: only the given "host:port" pairs answer. Records what was tried. */
    private class Probe(vararg open: String) {
        val open = open.toSet()
        val tried = mutableListOf<String>()
        val fn: suspend (String, Int) -> Boolean = { host, port -> "$host:$port".also { tried += it } in this.open }
    }

    @Test
    fun prefers_ipv4_when_it_answers() = runBlocking {
        val p = Probe("127.0.0.1:3000", "::1:3000")
        assertEquals("http://127.0.0.1:3000", loopbackOrigin("http://localhost:3000", p.fn))
        assertEquals(listOf("127.0.0.1:3000"), p.tried)
    }

    @Test
    fun falls_back_to_ipv6_for_ipv6_only_dev_servers() = runBlocking {
        val p = Probe("::1:5173")
        assertEquals("http://[::1]:5173", loopbackOrigin("http://localhost:5173", p.fn))
    }

    @Test
    fun null_when_nothing_answers() = runBlocking {
        assertNull(loopbackOrigin("http://localhost:3000", Probe().fn))
    }

    @Test
    fun non_localhost_targets_are_not_probed() = runBlocking {
        val p = Probe("127.0.0.1:3000")
        assertNull(loopbackOrigin("http://192.168.1.5:3000", p.fn))
        assertNull(loopbackOrigin("http://127.0.0.1:3000", p.fn))
        assertEquals(emptyList(), p.tried)
    }

    @Test
    fun default_ports_follow_the_scheme() = runBlocking {
        assertEquals("https://127.0.0.1:443", loopbackOrigin("https://LOCALHOST", Probe("127.0.0.1:443").fn))
        assertEquals("http://[::1]:80", loopbackOrigin("http://localhost", Probe("::1:80").fn))
    }
}
