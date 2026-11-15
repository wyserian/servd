package dev.servd.core.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeService(override val id: String) : Service {
    override val port: Int = 1234
    override val password: String = "pw"
    var current: ServiceState = ServiceState.Stopped
    override val state: ServiceState get() = current
    override suspend fun start() { current = ServiceState.Running }
    override suspend fun stop() { current = ServiceState.Stopped }
}

class ServiceManagerTest {

    @Test
    fun a_service_starts_and_stops() = runBlocking {
        val mgr = ServiceManager(listOf(FakeService("ssh")))

        assertTrue(mgr.start("ssh"))
        assertEquals("Running", mgr.list().first { it.id == "ssh" }.state)
        assertTrue(mgr.stop("ssh"))
        assertEquals("Stopped", mgr.list().first { it.id == "ssh" }.state)
    }

    @Test
    fun unknown_ids_are_rejected() = runBlocking {
        val mgr = ServiceManager(listOf(FakeService("ssh")))
        assertFalse(mgr.start("nope"))
        assertFalse(mgr.stop("nope"))
    }

    @Test
    fun list_reports_all_registered_services_in_order() {
        val mgr = ServiceManager(listOf(FakeService("ftp"), FakeService("ssh")))
        assertEquals(listOf("ftp", "ssh"), mgr.list().map { it.id })
    }
}
