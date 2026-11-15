package dev.servd.core.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

enum class ServiceState { Stopped, Starting, Running, Error }

/** An optional server servd runs beside its HTTPS dashboard (SSH/SFTP, FTPS), toggled from the admin panel. */
interface Service {
    val id: String // "ssh", "ftp"
    val port: Int
    val state: ServiceState

    /** The login password, shown to the host (admin panel only) so they can connect. */
    val password: String

    suspend fun start()
    suspend fun stop()
}

/** A [Service] as the admin API reports it. */
@Serializable
data class ServiceInfo(val id: String, val port: Int, val state: String, val password: String)

/** servd's optional services, started and stopped on request. */
class ServiceManager(services: List<Service>) {
    private val byId = services.associateBy { it.id }

    // Serialize all start/stop operations so a double-tap on a toggle can't start a service
    // twice (or race a start against a stop).
    private val lock = Mutex()

    fun list(): List<ServiceInfo> = byId.values.map { ServiceInfo(it.id, it.port, it.state.name, it.password) }

    /** Start a service. Returns false for an unknown id. */
    suspend fun start(id: String): Boolean = lock.withLock {
        val service = byId[id] ?: return@withLock false
        service.start()
        true
    }

    /** Stop a service. Returns false for an unknown id. */
    suspend fun stop(id: String): Boolean = lock.withLock {
        val service = byId[id] ?: return@withLock false
        service.stop()
        true
    }
}
