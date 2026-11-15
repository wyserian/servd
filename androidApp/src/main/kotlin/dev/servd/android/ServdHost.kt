package dev.servd.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.util.Log
import dev.servd.core.Servd
import dev.servd.core.discovery.LocalName
import dev.servd.core.net.detectLanAddress
import dev.servd.core.server.ServdServer
import dev.servd.core.service.FtpService
import dev.servd.core.service.ServdCredentials
import dev.servd.core.service.SshService
import dev.servd.core.tls.ServdCertificates
import io.ktor.server.netty.Netty
import java.io.File
import java.net.InetAddress

/**
 * The running servd hub on this phone. A process-wide singleton: the foreground service owns its
 * lifecycle (start/stop), and the control screen reads [info] to render the URL, fingerprint,
 * credentials, and QR. Hosts the exact same stack as desktop - HTTPS + chat + files, plus SSH and
 * FTP that stay off until enabled from the host-only admin panel.
 */
object ServdHost {
    const val PORT = 8443
    const val SSH_PORT = 2222
    const val FTP_PORT = 2121

    data class Info(
        val url: String,
        val adminUrl: String,
        val fingerprint: String,
        val username: String,
        val password: String,
        val sshPort: Int,
        val ftpPort: Int,
        val onLan: Boolean,
        /** Opens [adminUrl] as the host (see [dev.servd.core.service.ServdCredentials.loadOrCreateAdminToken]). */
        val adminToken: String,
        /** e.g. https://servd.local:8443, once the .local name is claimed; null if unavailable. */
        val localUrl: String? = null,
    )

    @Volatile private var server: ServdServer<*, *>? = null
    @Volatile var info: Info? = null
        private set

    val isRunning: Boolean get() = server != null

    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val localName = LocalName()
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Start the hub. Call off the main thread - it binds sockets and reads the filesystem. */
    @Synchronized
    fun start(context: Context) {
        if (server != null) return

        // MINA SSHD resolves user.home at class-init; Android has none, so point it at a real dir.
        System.setProperty("user.home", File(context.filesDir, "home").apply { mkdirs() }.absolutePath)

        val dataDir = File(context.filesDir, "servd")
        val filesDir = File(dataDir, "files")
        val lan = detectLanAddress()
        val advertisedHost = lan?.ip ?: "127.0.0.1"

        // An existing keystore is reused as-is (stable fingerprint); a new one also lists servd.local.
        val tls = ServdCertificates.loadOrCreate(dataDir, listOfNotNull("127.0.0.1", lan?.ip, "${LocalName.DEFAULT}.local"))
        val password = ServdCredentials.loadOrCreatePassword(dataDir)
        // Other apps on the phone also reach 127.0.0.1; only this app's link carries the token.
        val adminToken = ServdCredentials.loadOrCreateAdminToken(dataDir)
        val ssh = SshService(
            port = SSH_PORT,
            filesDir = filesDir,
            hostKeyFile = File(dataDir, "ssh_host_key.ser"),
            username = ServdCredentials.USERNAME,
            password = password,
        )
        val ftp = FtpService(
            port = FTP_PORT,
            filesDir = filesDir,
            tls = tls,
            username = ServdCredentials.USERNAME,
            password = password,
        )
        val srv = ServdServer(
            Netty, "0.0.0.0", advertisedHost, PORT, tls, filesDir, listOf(ftp, ssh),
            hostName = deviceName(),
            interfaceName = lan?.interfaceName,
            pickerRoots = storagePickerRoots(),
            pinFile = File(dataDir, "join.pin"),
            limitsFile = File(dataDir, "storage.properties"),
            adminToken = adminToken,
        )
        srv.start(wait = false)
        server = srv
        info = Info(
            url = "https://$advertisedHost:$PORT",
            adminUrl = "https://127.0.0.1:$PORT",
            fingerprint = tls.fingerprintSha256,
            username = ServdCredentials.USERNAME,
            password = password,
            sshPort = SSH_PORT,
            ftpPort = FTP_PORT,
            onLan = lan != null,
            adminToken = adminToken,
        )

        if (lan != null) {
            registerMdns(context, advertisedHost, tls.fingerprintSha256)
            startLocalName(context, advertisedHost, srv)
        }
    }

    @Synchronized
    fun stop() {
        stopLocalName()
        unregisterMdns()
        server?.let { runCatching { it.stop() } }
        server = null
        info = null
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return model.ifBlank { "android" }
    }

    /**
     * Where the host folder picker starts on Android. The true filesystem root "/" is mostly
     * unreadable, so we begin at the phone's shared storage (needs all-files access to list),
     * and still offer "/" for power users. From either, "up"/navigation reaches everything else.
     */
    private fun storagePickerRoots(): List<Pair<String, String>> {
        val roots = mutableListOf<Pair<String, String>>()
        runCatching {
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null && ext.isDirectory) roots += "Internal storage" to ext.absolutePath
        }
        roots += "Device root (/)" to "/"
        return roots
    }

    // Advertise over mDNS (same _servd._tcp service desktop uses) so other devices can discover
    // this hub without typing an IP. Best-effort - failure here never blocks hosting.
    private fun registerMdns(context: Context, host: String, fingerprint: String) {
        runCatching {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "servd@${deviceName()}"
                serviceType = "_servd._tcp."
                port = PORT
                setAttribute("version", Servd.VERSION)
                setAttribute("fingerprint", fingerprint)
                setAttribute("https", "true")
                setAttribute("path", "/")
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i("servd", "mDNS registered: ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w("servd", "mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            nsd = manager
            nsdListener = listener
        }.onFailure { Log.w("servd", "mDNS unavailable", it) }
    }

    // Answer for servd.local (or servd-2.local... if taken) so devices that resolve .local names
    // can open the hub by name. NsdManager can only advertise services, so this runs JmDNS, which
    // needs a multicast lock to hear queries over Wi-Fi. Best-effort, like the service advert.
    private fun startLocalName(context: Context, host: String, srv: ServdServer<*, *>) {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("servd-mdns").apply { setReferenceCounted(false); acquire() }
            localName.start(InetAddress.getByName(host), LocalName.DEFAULT)
            val name = localName.current ?: return
            srv.localName = name
            info = info?.copy(localUrl = "https://$name:$PORT")
        }.onFailure { Log.w("servd", ".local name unavailable", it) }
    }

    private fun stopLocalName() {
        localName.stop()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun unregisterMdns() {
        val manager = nsd
        val listener = nsdListener
        if (manager != null && listener != null) {
            runCatching { manager.unregisterService(listener) }
        }
        nsd = null
        nsdListener = null
    }
}
