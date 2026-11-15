package dev.servd.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File

/**
 * SSH service (Apache MINA SSHD). It exposes **SFTP only**, jailed to the shared files
 * directory - no interactive shell, so connecting never grants OS access to the host. Clients
 * authenticate with a generated username/password (shown in the host-only admin panel).
 * Hosts on both desktop and Android (Android needs `user.home` set before MINA SSHD class-init).
 */
class SshService(
    override val port: Int,
    private val filesDir: File,
    private val hostKeyFile: File,
    private val username: String,
    override val password: String,
) : Service {
    override val id: String = "ssh"

    @Volatile private var server: SshServer? = null
    @Volatile private var current: ServiceState = ServiceState.Stopped
    override val state: ServiceState get() = current

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (server != null) return@withContext
        current = ServiceState.Starting
        try {
            filesDir.mkdirs()
            val ssh = SshServer.setUpDefaultServer().apply {
                port = this@SshService.port
                // Give each server instance its own NIO factory so stopping it shuts down only
                // its own executor - the shared default one breaks a later restart.
                ioServiceFactoryFactory = Nio2ServiceFactoryFactory()
                keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
                passwordAuthenticator = PasswordAuthenticator { user, pass, _ ->
                    user == username && pass == password
                }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(filesDir.toPath())
            }
            ssh.start()
            server = ssh
            current = ServiceState.Running
        } catch (e: Exception) {
            current = ServiceState.Error
            throw e
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        server?.let { runCatching { it.stop(true) } }
        server = null
        current = ServiceState.Stopped
    }
}
