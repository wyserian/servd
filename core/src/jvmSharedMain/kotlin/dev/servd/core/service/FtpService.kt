package dev.servd.core.service

import dev.servd.core.tls.TlsKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.ftplet.Authentication
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.User
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.usermanager.AnonymousAuthentication
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File

/**
 * FTP service (Apache FtpServer) speaking **explicit FTPS** (AUTH TLS) using servd's own
 * self-signed keystore. Credentialed single user, home directory jailed to the shared files
 * directory. Hosts on both desktop and Android, toggled from the host admin panel.
 */
class FtpService(
    override val port: Int,
    private val filesDir: File,
    private val tls: TlsKeyStore,
    private val username: String,
    override val password: String,
) : Service {
    override val id: String = "ftp"

    @Volatile private var server: FtpServer? = null
    @Volatile private var current: ServiceState = ServiceState.Stopped
    override val state: ServiceState get() = current

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (server != null) return@withContext
        current = ServiceState.Starting
        try {
            filesDir.mkdirs()
            val factory = FtpServerFactory()

            // servd's own keystore: a plain PKCS12 with one password for store and key, as
            // FtpServer's key manager needs.
            val ssl = SslConfigurationFactory().apply {
                keystoreFile = tls.file
                keystorePassword = tls.keyStorePassword
                keyPassword = tls.privateKeyPassword
                keystoreType = "PKCS12"
                keyAlias = tls.alias
            }
            val sslConfig = ssl.createSslConfiguration()
            // The data channel needs TLS too (clients send PROT P); reuse the same config.
            val dataConfig = DataConnectionConfigurationFactory().apply {
                sslConfiguration = sslConfig
            }.createDataConnectionConfiguration()
            val listener = ListenerFactory().apply {
                port = this@FtpService.port
                sslConfiguration = sslConfig
                isImplicitSsl = false // explicit FTPS: client issues AUTH TLS
                dataConnectionConfiguration = dataConfig
            }
            factory.addListener("default", listener.createListener())

            val user = BaseUser().apply {
                name = username
                this.password = this@FtpService.password
                homeDirectory = filesDir.absolutePath
                authorities = listOf<Authority>(
                    WritePermission(),
                    ConcurrentLoginPermission(64, 64), // allow reconnects / parallel transfers
                )
            }
            factory.userManager = SingleUserManager(user, this@FtpService.password)

            val s = factory.createServer()
            s.start()
            server = s
            current = ServiceState.Running
        } catch (e: Exception) {
            current = ServiceState.Error
            throw e
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        server?.let { runCatching { it.stop() } }
        server = null
        current = ServiceState.Stopped
    }
}

/** A minimal in-memory UserManager for servd's single FTP user. */
private class SingleUserManager(
    private val user: BaseUser,
    private val password: String,
) : UserManager {
    override fun getUserByName(name: String?): User? = if (name == user.name) user else null
    override fun getAllUserNames(): Array<String> = arrayOf(user.name)
    override fun delete(name: String?) {}
    override fun save(u: User?) {}
    override fun doesExist(name: String?): Boolean = name == user.name
    override fun getAdminName(): String = user.name
    override fun isAdmin(name: String?): Boolean = false

    override fun authenticate(authentication: Authentication): User {
        if (authentication is UsernamePasswordAuthentication &&
            authentication.username == user.name &&
            authentication.password == password
        ) {
            return user
        }
        if (authentication is AnonymousAuthentication) {
            throw org.apache.ftpserver.ftplet.AuthenticationFailedException("anonymous not allowed")
        }
        throw org.apache.ftpserver.ftplet.AuthenticationFailedException("authentication failed")
    }
}
