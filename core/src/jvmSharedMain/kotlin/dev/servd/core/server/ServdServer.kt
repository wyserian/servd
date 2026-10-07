package dev.servd.core.server

import dev.servd.core.Servd
import dev.servd.core.browse.Browser
import dev.servd.core.proxy.WebProxy
import dev.servd.core.chat.ChatHub
import dev.servd.core.chat.ChatSend
import dev.servd.core.chat.ClearChat
import dev.servd.core.chat.FileMeta
import dev.servd.core.chat.Hello
import dev.servd.core.files.FileStore
import dev.servd.core.qr.Qr
import dev.servd.core.service.HttpService
import dev.servd.core.service.Service
import dev.servd.core.service.ServiceManager
import dev.servd.core.tls.TlsKeyStore
import io.ktor.server.application.ApplicationCall
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.InetAddress
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * servd's single HTTPS server. Phase 1 serves the dashboard shell and a `/status` endpoint;
 * later phases add WebSocket chat, presence, and file sharing on the same server.
 *
 * The routing/module and TLS wiring here are engine-agnostic; the concrete Ktor engine is
 * passed in by each platform - Netty on desktop (Ktor CIO does not support server HTTPS).
 * Lives in `jvmSharedMain` so the same server logic runs on desktop now and Android later.
 */
class ServdServer<TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration>(
    private val engineFactory: ApplicationEngineFactory<TEngine, TConfiguration>,
    /** Interface to listen on (e.g. "0.0.0.0" for all interfaces). */
    private val bindHost: String,
    /** Address shown to users / used in the dashboard URL (e.g. the LAN IP). */
    private val advertisedHost: String,
    val port: Int,
    private val tls: TlsKeyStore,
    /** Directory where shared files are stored. */
    filesDir: File,
    /** Platform services (e.g. SSH/FTP on desktop) added alongside the always-on HTTP one. */
    extraServices: List<Service> = emptyList(),
    /** Machine host name, shown in the host card. */
    private val hostName: String = "servd",
    /** Bound network interface name (e.g. "wlan0"), shown in the host card. */
    private val interfaceName: String? = null,
    /** Optional initial browse root (a folder served to clients); off if null. */
    browseDir: String? = null,
    /** Whether the initial browse root allows uploads. */
    browseWritable: Boolean = false,
    /** Starting folders (label to path) for the host folder picker; empty = filesystem roots. */
    pickerRoots: List<Pair<String, String>> = emptyList(),
    /** Optional initial reverse-proxy target (a host-run web app re-served to the LAN); off if null. */
    proxyTarget: String? = null,
    /** Dedicated port the reverse proxy listens on. */
    proxyPort: Int = 8080,
) {
    val url: String get() = "https://$advertisedHost:$port"

    private val startedAt = System.currentTimeMillis()
    private val chatHub = ChatHub(serverName = advertisedHost)
    private val fileStore = FileStore(filesDir)
    private val browser = Browser().also { b ->
        b.pickerRoots = pickerRoots.map { (label, path) -> Browser.PickerDir(label, path) }
        if (browseDir != null) runCatching { b.enable(browseDir, browseWritable) }
    }
    private val webProxy = WebProxy(engineFactory, bindHost, advertisedHost, tls).also { p ->
        if (proxyTarget != null) runCatching { p.enable(proxyTarget, proxyPort) }
    }
    private val serviceManager = ServiceManager(listOf(HttpService(port)) + extraServices)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // The dashboard is one resource; read it by name (works on desktop and Android).
    private val indexHtml: ByteArray? by lazy {
        javaClass.classLoader?.getResourceAsStream("webui/index.html")?.use { it.readBytes() }
    }

    private val engine = embeddedServer(
        engineFactory,
        applicationEnvironment { },
        configure = {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.keyStorePasswordChars() },
                privateKeyPassword = { tls.privateKeyPasswordChars() },
            ) {
                host = bindHost
                port = this@ServdServer.port
            }
        },
        module = { servdModule() },
    )

    fun start(wait: Boolean) {
        engine.start(wait)
    }

    fun stop() {
        runCatching { webProxy.disable() }
        engine.stop(gracePeriodMillis = 300, timeoutMillis = 1500)
    }

    private fun Application.servdModule() {
        install(WebSockets)
        routing {
            // Real-time chat + presence. Clients connect, say hello (name), then exchange chat.
            webSocket("/ws") {
                val address = call.request.origin.remoteAddress // raw IP, no reverse-DNS
                val id = chatHub.onConnect(this, address)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            when (val msg = chatHub.parseClient(frame.readText())) {
                                is Hello -> chatHub.onHello(id, msg.name)
                                is ChatSend -> chatHub.onChat(id, msg.text)
                                is ClearChat -> chatHub.onClearChat(id)
                                null -> {}
                            }
                        }
                    }
                } finally {
                    chatHub.onDisconnect(id)
                }
            }
            // Machine-readable status - includes the cert fingerprint for verification.
            get("/status") {
                val ifaceJson = interfaceName?.let { "\"$it\"" } ?: "null"
                val json = buildString {
                    append('{')
                    append("\"name\":\"").append(Servd.NAME).append("\",")
                    append("\"version\":\"").append(Servd.VERSION).append("\",")
                    append("\"address\":\"").append(advertisedHost).append("\",")
                    append("\"port\":").append(port).append(',')
                    append("\"hostName\":\"").append(hostName).append("\",")
                    append("\"interfaceName\":").append(ifaceJson).append(',')
                    append("\"uptimeMs\":").append(System.currentTimeMillis() - startedAt).append(',')
                    append("\"connected\":").append(chatHub.connectionCount()).append(',')
                    append("\"tls\":\"self-signed\",")
                    append("\"fingerprintSha256\":\"").append(tls.fingerprintSha256).append('"')
                    append('}')
                }
                call.respondText(json, ContentType.Application.Json)
            }
            // QR code (SVG) encoding the shareable hub URL, so a phone can scan to join.
            get("/qr") {
                call.respondText(Qr.svg("$url/"), ContentType("image", "svg+xml"))
            }
            // File sharing: upload (multipart), list (newest first), download (by id).
            post("/files") {
                // Ktor caps multipart parts at ~50 MB by default, which silently 500s bigger uploads.
                // File parts are streamed straight to disk below, so lift the cap well up (5 GB).
                val multipart = call.receiveMultipart(formFieldLimit = 5L * 1024 * 1024 * 1024)
                var from = "someone"
                val saved = mutableListOf<FileMeta>()
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem ->
                            if (part.name == "from") from = part.value.take(40).ifBlank { "someone" }
                        is PartData.FileItem -> {
                            val name = part.originalFileName ?: "file"
                            val contentType = part.contentType?.toString()
                            val meta = part.provider().toInputStream().use {
                                fileStore.save(name, contentType, from, it)
                            }
                            chatHub.announceFile(meta)
                            saved += meta
                        }
                        else -> {}
                    }
                    part.dispose()
                }
                call.respondText(json.encodeToString(saved), ContentType.Application.Json)
            }
            get("/files") {
                call.respondText(json.encodeToString(fileStore.list()), ContentType.Application.Json)
            }
            // Every shared file in one zip, streamed (nothing buffered in memory). Duplicate names
            // get a " (2)" suffix so no entry overwrites another when extracted.
            get("/files/zip") {
                val entries = fileStore.entries()
                if (entries.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, "servd-files.zip")
                        .toString(),
                )
                call.respondOutputStream(ContentType.Application.Zip) {
                    ZipOutputStream(this).use { zip ->
                        zip.setLevel(Deflater.BEST_SPEED)
                        val used = HashSet<String>()
                        for ((meta, file) in entries) {
                            zip.putNextEntry(ZipEntry(uniqueName(meta.name, used)))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            get("/files/{id}") {
                val entry = call.parameters["id"]?.let { fileStore.get(it) }
                if (entry == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val (meta, file) = entry
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, meta.name)
                        .toString(),
                )
                call.respondFile(file)
            }
            delete("/files/{id}") {
                val removed = call.parameters["id"]?.let { fileStore.remove(it) }
                if (removed == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    chatHub.announceFileRemoved(removed.id)
                    call.respond(HttpStatusCode.OK)
                }
            }
            delete("/files") {
                val from = call.request.queryParameters["from"]?.take(40)?.ifBlank { "someone" } ?: "someone"
                fileStore.clear()
                chatHub.announceFilesCleared(from)
                call.respond(HttpStatusCode.OK)
            }
            // ---- Browse: a host-chosen directory served to LAN clients (off unless the host
            // enables it from the admin panel). Paths are relative to the root and jailed inside it.
            get("/fs/info") {
                call.respondText(json.encodeToString(browser.info()), ContentType.Application.Json)
            }
            get("/fs/list") {
                if (!browser.enabled) return@get call.respond(HttpStatusCode.NotFound)
                val listing = browser.list(call.request.queryParameters["path"] ?: "")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondText(json.encodeToString(listing), ContentType.Application.Json)
            }
            get("/fs/file") {
                if (!browser.enabled) return@get call.respond(HttpStatusCode.NotFound)
                val f = call.request.queryParameters["path"]?.let { browser.download(it) }
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, f.name).toString(),
                )
                call.respondFile(f)
            }
            post("/fs/upload") {
                if (!browser.enabled) return@post call.respond(HttpStatusCode.NotFound)
                if (!browser.writable) return@post call.respond(HttpStatusCode.Forbidden)
                val dir = call.request.queryParameters["path"] ?: ""
                val multipart = call.receiveMultipart(formFieldLimit = 5L * 1024 * 1024 * 1024)
                var ok = false
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val name = part.originalFileName ?: "file"
                        val saved = part.provider().toInputStream().use { browser.upload(dir, name, it) }
                        ok = saved || ok
                    }
                    part.dispose()
                }
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }

            // Host-only admin API: reachable from loopback (the host machine) only, so LAN
            // devices can use the dashboard but cannot reconfigure the server.
            get("/admin/browse") {
                if (!call.isLoopbackClient()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(json.encodeToString(browser.config()), ContentType.Application.Json)
            }
            post("/admin/browse") {
                if (!call.isLoopbackClient()) return@post call.respond(HttpStatusCode.Forbidden)
                val p = call.request.queryParameters
                val enable = p["enabled"]?.toBoolean() ?: false
                val writable = p["writable"]?.toBoolean() ?: false
                val path = p["path"]
                val ok = runCatching {
                    if (enable && !path.isNullOrBlank()) browser.enable(path, writable) else browser.disable()
                }.isSuccess
                call.respondText(
                    json.encodeToString(browser.config()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            get("/admin/browse/dirs") {
                if (!call.isLoopbackClient()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(
                    json.encodeToString(browser.serverDirs(call.request.queryParameters["path"])),
                    ContentType.Application.Json,
                )
            }
            // Reverse proxy (re-serve a host-run web app to the LAN), host-only.
            get("/admin/proxy") {
                if (!call.isLoopbackClient()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(json.encodeToString(webProxy.config()), ContentType.Application.Json)
            }
            post("/admin/proxy") {
                if (!call.isLoopbackClient()) return@post call.respond(HttpStatusCode.Forbidden)
                val p = call.request.queryParameters
                val enable = p["enabled"]?.toBoolean() ?: false
                val target = p["target"]
                val proxyPort = p["port"]?.toIntOrNull() ?: 8080
                val ok = runCatching {
                    if (enable && !target.isNullOrBlank()) webProxy.enable(target, proxyPort) else webProxy.disable()
                }.isSuccess
                call.respondText(
                    json.encodeToString(webProxy.config()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            get("/admin/services") {
                if (!call.isLoopbackClient()) return@get call.respond(HttpStatusCode.Forbidden)
                call.respondText(json.encodeToString(serviceManager.list()), ContentType.Application.Json)
            }
            post("/admin/services/{id}/start") {
                if (!call.isLoopbackClient()) return@post call.respond(HttpStatusCode.Forbidden)
                val ok = call.parameters["id"]?.let { serviceManager.start(it) } ?: false
                call.respondText(
                    json.encodeToString(serviceManager.list()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            post("/admin/services/{id}/stop") {
                if (!call.isLoopbackClient()) return@post call.respond(HttpStatusCode.Forbidden)
                val ok = call.parameters["id"]?.let { serviceManager.stop(it) } ?: false
                call.respondText(
                    json.encodeToString(serviceManager.list()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            // The served dashboard (single page). Read the one resource by name via the
            // classloader - Ktor's directory-style staticResources does not resolve on Android.
            get("/") {
                val html = indexHtml
                if (html != null) call.respondBytes(html, ContentType.Text.Html)
                else call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

/** [name], or "name (2).ext", "name (3).ext"… if already in [used]. Records the result in [used]. */
private fun uniqueName(name: String, used: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (!used.add(candidate.lowercase())) {
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
        n++
    }
    return candidate
}

/** True only when the request came from this machine (loopback), gating the admin API. */
private fun ApplicationCall.isLoopbackClient(): Boolean =
    runCatching { InetAddress.getByName(request.origin.remoteAddress).isLoopbackAddress }
        .getOrDefault(false)
