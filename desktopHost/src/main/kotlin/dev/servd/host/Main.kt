package dev.servd.host

import dev.servd.core.Servd
import dev.servd.core.auth.JoinPin
import dev.servd.core.discovery.LocalName
import dev.servd.core.discovery.MdnsAdvertiser
import dev.servd.core.discovery.MdnsBrowser
import dev.servd.core.net.detectLanAddress
import dev.servd.core.server.ServdServer
import dev.servd.core.service.FtpService
import dev.servd.core.service.ServdCredentials
import dev.servd.core.service.SshService
import dev.servd.core.tls.ServdCertificates
import io.ktor.server.netty.Netty
import java.awt.Desktop
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import dev.servd.core.tls.TrustAllCerts
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import kotlin.system.exitProcess

/**
 * servd desktop entrypoint.
 *
 *   servd [start] [--port N] [--host IP] [--dir PATH] [--no-open]   start the hub
 *       [--browse-dir PATH [--browse-write]]                        also serve a folder to clients
 *       [--proxy-target URL [--proxy-port N]]                       also re-serve a live web app
 *       [--pin DIGITS | --pin off]                                  set (and save) or clear the join PIN
 *       [--max-storage 10G|off] [--expire 1h|24h|7d|off]            drop-files quota / auto-clear (saved)
 *       [--name NAME | --name off]                                  .local name over mDNS (default servd)
 *   servd discover                                                  find hubs on the network
 */
fun main(args: Array<String>) {
    // Quiet the benign Netty TLS-handshake warnings logged when a browser declines the
    // self-signed cert before the user clicks "proceed". Set before any logging happens.
    System.setProperty("org.slf4j.simpleLogger.log.io.netty", "error")
    // Silence the Ktor Netty dispatch logger. On Ctrl+C it floods the console with
    // "RejectedExecutionException: event executor terminated" - an in-flight HTTP/2 frame
    // racing the event loop's shutdown. The server stops cleanly regardless; this is only noise.
    System.setProperty("org.slf4j.simpleLogger.log.io.ktor.server.netty.CIO", "off")

    val opts = Opts.parse(args)
    val dataDir = File(opts.dir ?: (System.getProperty("user.home") + File.separator + ".servd"))
    useOwnSocketDir(dataDir)

    if (args.firstOrNull() == "discover") {
        runDiscover()
        return
    }

    val lan = detectLanAddress()
    // Bind all interfaces by default (so localhost AND the LAN IP both work); a specific
    // --host overrides both. Advertise the LAN IP in the URL for other devices.
    val advertisedHost = opts.host ?: lan?.ip ?: "127.0.0.1"
    val bindHost = opts.host ?: "0.0.0.0"

    // The host's dashboard is opened with this token; without it, loopback alone isn't admin.
    val adminToken = ServdCredentials.loadOrCreateAdminToken(dataDir)

    println("${Servd.NAME} v${Servd.VERSION} - ${Servd.TAGLINE}")
    if (!portFree(bindHost, opts.port)) portInUse(opts.port, opts.noOpen, adminToken)
    if (opts.host == null && lan == null) {
        println("(no LAN address found - reachable at 127.0.0.1 only; connect Wi-Fi/hotspot for other devices)")
    }

    // The .local name to answer over mDNS (servd.local by default; --name off = none).
    val friendlyName: String? = when (val n = opts.name?.trim()?.lowercase()) {
        null -> LocalName.DEFAULT
        "off" -> null
        else -> n.takeIf { LocalName.isValid(it) }
            ?: usageError("--name takes letters, digits and \"-\" (e.g. --name myhub for myhub.local), or \"off\".")
    }

    // An existing keystore is reused as-is (so its fingerprint stays the one people verified);
    // only a newly created certificate also lists the .local name.
    val tls = ServdCertificates.loadOrCreate(dataDir, listOfNotNull(advertisedHost, lan?.ip, friendlyName?.let { "$it.local" }))
    val filesDir = File(dataDir, "files")
    // Optional services (off until enabled from the admin panel), sharing one credential.
    val servdPassword = ServdCredentials.loadOrCreatePassword(dataDir)
    val sshService = SshService(
        port = 2222,
        filesDir = filesDir,
        hostKeyFile = File(dataDir, "ssh_host_key.ser"),
        username = ServdCredentials.USERNAME,
        password = servdPassword,
    )
    val ftpService = FtpService(
        port = 2121,
        filesDir = filesDir,
        tls = tls,
        username = ServdCredentials.USERNAME,
        password = servdPassword,
    )
    val server = ServdServer(
        Netty, bindHost, advertisedHost, opts.port, tls, filesDir, listOf(ftpService, sshService),
        hostName = hostName(),
        interfaceName = lan?.interfaceName,
        browseDir = opts.browseDir,
        browseWritable = opts.browseWrite,
        proxyTarget = opts.proxyTarget,
        proxyPort = opts.proxyPort,
        pinFile = File(dataDir, "join.pin"),
        limitsFile = File(dataDir, "storage.properties"),
        adminToken = adminToken,
    )
    if (opts.maxStorage != null || opts.expire != null) {
        // A flag replaces that saved limit ("off" clears it); the other one is kept.
        val limits = server.dropLimits
        val maxBytes = opts.maxStorage?.let { parseSize(it) ?: usageError("--max-storage takes a size like 10G or 500M (or \"off\").") }
            ?: limits.maxBytes
        val expireHours = opts.expire?.let { parseExpiry(it) ?: usageError("--expire takes 1h, 24h or 7d (or \"off\").") }
            ?: limits.expireHours
        limits.set(maxBytes.takeIf { it != OFF }, expireHours.takeIf { it != OFF.toInt() })
    }
    when (opts.pin) {
        null -> {}
        "off" -> server.joinPin.set(null)
        else -> if (JoinPin.isValid(opts.pin)) server.joinPin.set(opts.pin) else {
            println("--pin must be 4-8 digits (or \"off\").")
            exitProcess(1)
        }
    }

    // When bound to all interfaces, the host reaches admin via loopback; a specific --host
    // means admin is only available if that host is itself loopback.
    val localUrl = if (bindHost == "0.0.0.0") adminUrl(opts.port, adminToken) else server.url

    // Advertise over mDNS so other devices can find this hub without typing an IP: the
    // _servd._tcp service (for `servd discover`) and the friendly .local name (e.g. servd.local).
    val advertiser = MdnsAdvertiser()
    val advertised = runCatching {
        advertiser.start(
            bindAddress = InetAddress.getByName(advertisedHost),
            hostName = friendlyName,
            instanceName = "servd@" + hostName(),
            port = opts.port,
            txt = mapOf(
                "version" to Servd.VERSION,
                "fingerprint" to tls.fingerprintSha256,
                "https" to "true",
                "path" to "/",
            ),
        )
    }.isSuccess
    // Only when it's on the LAN: a loopback-only hub has no one to answer.
    server.localName = advertiser.hostName.takeIf { advertised && friendlyName != null && lan != null }

    println()
    if (bindHost == "0.0.0.0") {
        println("admin   : $localUrl   (this machine only - keep the link private)")
    }
    println("serving : ${server.url}   (share with other devices)")
    server.localName?.let { println("by name : https://$it:${opts.port}   (iPhone, Mac, Windows, Linux - Android may need the IP)") }
    if (opts.proxyTarget != null) {
        println("proxy   : https://$advertisedHost:${opts.proxyPort}   ->  ${opts.proxyTarget}   (live site)")
    }
    println("cert    : self-signed, SHA-256 fingerprint:")
    println("          ${tls.fingerprintSha256}")
    println("keystore: ${tls.file}")
    server.joinPin.current?.let { println("join PIN: $it   (other devices must enter it)") }
    server.dropLimits.let { l ->
        if (l.maxBytes != null || l.expireHours != null) {
            val quota = l.maxBytes?.let { "quota ${it / (1L shl 20)} MB" }
            val clear = l.expireHours?.let { "auto-clear after ${if (it % 24 == 0) "${it / 24} d" else "$it h"}" }
            println("files   : ${listOfNotNull(quota, clear).joinToString(", ")}")
        }
    }
    println()
    Runtime.getRuntime().addShutdownHook(Thread { println("\nshutting down..."); advertiser.stop(); server.stop() })

    println("Open that URL on any device on this network. The browser will warn about the")
    println("self-signed certificate - that's expected; verify the fingerprint above, then proceed.")
    if (advertised) println("discovery: advertising _servd._tcp - other devices can run `servd discover`.")
    println("services: SSH/SFTP (2222) and FTPS (2121) available - enable them from the admin panel.")
    println("Press Ctrl+C to stop.")

    if (!opts.noOpen) openBrowserSoon(localUrl)

    try {
        server.start(wait = true)
    } catch (e: Exception) {
        // Lost a race for the port after the check above (or another program holds it only on
        // the address we bind): same friendly message instead of a stack trace.
        if (generateSequence<Throwable>(e) { it.cause }.any { it is BindException }) portInUse(opts.port, noOpen = true, adminToken)
        throw e
    }
}

/**
 * JDK 16+ on Windows backs NIO's internal selector pipe with an AF_UNIX socket file in the temp
 * dir. Some machines reject socket files there (AV/EDR policies, or a temp path longer than the
 * AF_UNIX limit), and every selector - so the whole server - fails with "Unable to establish
 * loopback connection". Use a short folder servd owns instead. Must run before any NIO use, as
 * the JDK reads this property once. An explicit -Djdk.net.unixdomain.tmpdir wins.
 */
private fun useOwnSocketDir(dataDir: File) {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    if (System.getProperty("jdk.net.unixdomain.tmpdir") != null) return
    // AF_UNIX paths max out near 108 chars and the JDK appends a socket name, so a long --dir
    // falls back to the home folder.
    val dir = listOf(File(dataDir, "sock"), File(System.getProperty("user.home"), ".servd${File.separator}sock"))
        .firstOrNull { it.absolutePath.length <= 80 } ?: return
    if (dir.isDirectory || dir.mkdirs()) System.setProperty("jdk.net.unixdomain.tmpdir", dir.absolutePath)
}

/** Marks "off" in [parseSize] / [parseExpiry] (distinct from null = unparseable). */
private const val OFF = -1L

/** "10G", "500M", "2048K" or plain bytes; "off" = [OFF]; null if unparseable or not positive. */
private fun parseSize(s: String): Long? {
    if (s.equals("off", ignoreCase = true)) return OFF
    val m = Regex("""(\d+(?:\.\d+)?)\s*([KMG]?)B?""", RegexOption.IGNORE_CASE).matchEntire(s.trim()) ?: return null
    val unit = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; else -> 1L }
    return (m.groupValues[1].toDouble() * unit).toLong().takeIf { it > 0 }
}

/** "1h", "24h" or "7d" as hours; "off" = [OFF]; null if not one of the allowed choices. */
private fun parseExpiry(s: String): Int? = when (s.trim().lowercase()) {
    "off" -> OFF.toInt()
    "1h" -> 1
    "24h", "1d" -> 24
    "7d", "168h" -> 168
    else -> null
}

private fun usageError(message: String): Nothing {
    println(message)
    exitProcess(1)
}

/** The host's dashboard link: loopback plus the admin token (the hub swaps it for a cookie). */
private fun adminUrl(port: Int, adminToken: String) = "https://127.0.0.1:$port/?admin=$adminToken"

private fun portFree(host: String, port: Int): Boolean =
    runCatching { ServerSocket().use { it.bind(InetSocketAddress(host, port)) } }.isSuccess

/**
 * The HTTPS port is taken. If it's another servd (the usual case - one left running), open its
 * dashboard and exit; otherwise explain and wait for Enter, so a double-clicked console window
 * doesn't vanish before the message can be read.
 */
private fun portInUse(port: Int, noOpen: Boolean, adminToken: String): Nothing {
    val running = runningServdVersion(port)
    println()
    if (running != null) {
        println("servd v$running is already running on port $port: https://127.0.0.1:$port")
        println("Stop it first, or start another hub with --port N.")
        // Same data folder, same token (a hub started with another --dir just opens as a guest view).
        if (!noOpen) runCatching { Desktop.getDesktop().browse(URI(adminUrl(port, adminToken))) }
        exitProcess(0)
    }
    println("Port $port is already in use by another program.")
    println("Close that program, or start servd on another port: servd --port 9443")
    if (System.console() != null) {
        println("Press Enter to exit.")
        readlnOrNull()
    }
    exitProcess(1)
}

/** The version of the servd answering on loopback [port], or null if it isn't servd. */
private fun runningServdVersion(port: Int): String? = runCatching {
    // Our own self-signed cert on our own machine: skip verification for this one probe.
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustAllCerts), null) }
    val conn = URI("https://127.0.0.1:$port/status").toURL().openConnection() as HttpsURLConnection
    conn.sslSocketFactory = ssl.socketFactory
    conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
    conn.connectTimeout = 1500
    conn.readTimeout = 1500
    val body = conn.inputStream.bufferedReader().use { it.readText() }
    if (!body.contains("\"name\":\"${Servd.NAME}\"")) return@runCatching null
    Regex("\"version\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "?"
}.getOrNull()

private fun runDiscover() {
    println("${Servd.NAME} - searching for hubs on the local network (~3s)...")
    val lan = detectLanAddress()
    val bind = runCatching { lan?.ip?.let { InetAddress.getByName(it) } }.getOrNull()
    val hubs = runCatching { MdnsBrowser().discover(durationMillis = 3000, bindAddress = bind) }.getOrDefault(emptyList())
    println()
    if (hubs.isEmpty()) {
        println("No servd hubs found. Make sure a hub is running on this network.")
    } else {
        println("Found ${hubs.size} hub(s):")
        hubs.forEach { println("  ${it.url}   ${it.name}   v${it.version ?: "?"}") }
    }
}

private fun hostName(): String =
    runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.') ?: "host"

private fun openBrowserSoon(url: String) {
    Thread {
        runCatching {
            Thread.sleep(900)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }.apply { isDaemon = true }.start()
}

private data class Opts(
    val port: Int = 8443,
    val host: String? = null,
    val dir: String? = null,
    val noOpen: Boolean = false,
    val browseDir: String? = null,
    val browseWrite: Boolean = false,
    val proxyTarget: String? = null,
    val proxyPort: Int = 8080,
    val pin: String? = null,
    val maxStorage: String? = null,
    val expire: String? = null,
    val name: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): Opts {
            var port = 8443
            var host: String? = null
            var dir: String? = null
            var noOpen = false
            var browseDir: String? = null
            var browseWrite = false
            var proxyTarget: String? = null
            var proxyPort = 8080
            var pin: String? = null
            var maxStorage: String? = null
            var expire: String? = null
            var name: String? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "start" -> {} // optional verb
                    "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
                    "--host" -> host = args.getOrNull(++i)
                    "--dir" -> dir = args.getOrNull(++i)
                    "--no-open" -> noOpen = true
                    "--browse-dir" -> browseDir = args.getOrNull(++i)
                    "--browse-write" -> browseWrite = true
                    "--proxy-target" -> proxyTarget = args.getOrNull(++i)
                    "--proxy-port" -> proxyPort = args.getOrNull(++i)?.toIntOrNull() ?: proxyPort
                    "--pin" -> pin = args.getOrNull(++i)
                    "--max-storage" -> maxStorage = args.getOrNull(++i)
                    "--expire" -> expire = args.getOrNull(++i)
                    "--name" -> name = args.getOrNull(++i)
                }
                i++
            }
            return Opts(port, host, dir, noOpen, browseDir, browseWrite, proxyTarget, proxyPort, pin, maxStorage, expire, name)
        }
    }
}
