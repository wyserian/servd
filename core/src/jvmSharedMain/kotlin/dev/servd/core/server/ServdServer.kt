package dev.servd.core.server

import dev.servd.core.Servd
import dev.servd.core.auth.JoinPin
import dev.servd.core.browse.Browser
import dev.servd.core.browse.numberedName
import dev.servd.core.chat.peerIdFor
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondRedirect
import java.security.MessageDigest
import dev.servd.core.proxy.WebProxy
import dev.servd.core.chat.ChatHub
import dev.servd.core.chat.ChatSend
import dev.servd.core.chat.ClearChat
import dev.servd.core.chat.Hello
import dev.servd.core.chat.LiveLeave
import dev.servd.core.chat.LiveStart
import dev.servd.core.chat.LiveStop
import dev.servd.core.chat.LiveWatch
import dev.servd.core.chat.MediaPlay
import dev.servd.core.chat.MediaPlayUrl
import dev.servd.core.files.MediaLink
import dev.servd.core.chat.MediaState
import dev.servd.core.chat.MediaStop
import dev.servd.core.chat.SignalSend
import dev.servd.core.files.DropLimits
import dev.servd.core.files.FileStore
import dev.servd.core.files.MediaTypes
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.partialcontent.PartialContent
import dev.servd.core.qr.Qr
import dev.servd.core.service.HttpService
import dev.servd.core.service.Service
import dev.servd.core.service.ServiceManager
import dev.servd.core.tls.TlsKeyStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

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
    /** Where the optional join PIN is persisted; null keeps it in memory only. */
    pinFile: File? = null,
    /** Where the drop-files limits (quota, auto-clear) are persisted; null keeps them in memory only. */
    limitsFile: File? = null,
    /** Secret the host's dashboard must present for the admin API (see [ServdCredentials.loadOrCreateAdminToken]). */
    private val adminToken: String,
) {
    val url: String get() = "https://$advertisedHost:$port"

    /** Optional join PIN (off by default); the host can also set it before [start]. */
    val joinPin = JoinPin(pinFile)

    /** The .local name this hub answers to over mDNS (e.g. "servd.local"), set by the platform once claimed. */
    @Volatile var localName: String? = null

    private val startedAt = System.currentTimeMillis()
    private val chatHub = ChatHub(serverName = advertisedHost)
    private val fileStore = FileStore(filesDir, DropLimits(limitsFile))

    /** Drop-files quota and auto-clear (both off by default); the host can also set them before [start]. */
    val dropLimits: DropLimits get() = fileStore.limits
    private val browser = Browser().also { b ->
        b.pickerRoots = pickerRoots.map { (label, path) -> Browser.PickerDir(label, path) }
        if (browseDir != null) runCatching { b.enable(browseDir, browseWritable) }
    }
    // Cookies ignore ports, so the hub's session cookie also unlocks the proxy's port.
    private val webProxy = WebProxy(engineFactory, bindHost, advertisedHost, tls, allows = { it.mayUseHub() }, hubUrl = url).also { p ->
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

    /** The host (loopback) always may; other devices need a valid session while a PIN is set. */
    private fun ApplicationCall.mayUseHub(): Boolean =
        isLoopbackClient() || joinPin.allows(request.cookies[JoinPin.COOKIE])

    /** The host's own dashboard: on loopback AND holding the admin token (its SameSite=Strict cookie). */
    private fun ApplicationCall.isHost(): Boolean =
        isLoopbackClient() && MessageDigest.isEqual(
            request.cookies[ADMIN_COOKIE].orEmpty().toByteArray(), adminToken.toByteArray(),
        )

    private fun Application.servdModule() {
        // Chat and WebRTC setup messages are small; without a cap one device could make the hub
        // buffer a multi-GB frame and run out of memory.
        install(WebSockets) { maxFrameSize = MAX_WS_FRAME }
        install(PartialContent) // Range requests: video previews can seek
        // Auto-clear sweep (a no-op while auto-clear is off). Stops with the server.
        launch {
            while (isActive) {
                delay(60_000)
                runCatching { expireFiles() }
            }
        }
        // One gate for every request: the admin API needs the host, and while a PIN is set
        // everything except the page itself and the PIN check needs a session.
        intercept(ApplicationCallPipeline.Plugins) {
            val denied = accessDenied(
                call.request.path(), call.isLoopbackClient(),
                session = joinPin.allows(call.request.cookies[JoinPin.COOKIE]), host = call.isHost(),
            )
            if (denied != null) {
                call.respond(denied)
                finish()
            }
        }
        routing {
            get("/auth") {
                val required = joinPin.enabled && !call.isLoopbackClient()
                call.respondText("""{"required":$required,"ok":${call.mayUseHub()}}""", ContentType.Application.Json)
            }
            post("/auth") {
                val entered = call.receiveParameters()["pin"].orEmpty()
                when (val r = joinPin.attempt(call.request.origin.remoteAddress, entered)) {
                    is JoinPin.Attempt.Ok -> {
                        // Written by hand: Ktor's cookie renderer appends a non-standard "$x-enc" attribute.
                        // The token is base64url, so it needs no encoding. Port-agnostic, so it also
                        // unlocks the serve-site port.
                        call.response.header(
                            HttpHeaders.SetCookie,
                            "${JoinPin.COOKIE}=${r.token}; Max-Age=${30 * 24 * 3600}; Path=/; Secure; HttpOnly; SameSite=Lax",
                        )
                        call.respondText("""{"ok":true}""", ContentType.Application.Json)
                    }
                    is JoinPin.Attempt.Wrong -> call.respondText(
                        """{"ok":false,"triesLeft":${r.triesLeft}}""", ContentType.Application.Json, HttpStatusCode.Unauthorized,
                    )
                    is JoinPin.Attempt.Locked -> call.respondText(
                        """{"ok":false,"retryAfterMs":${r.retryAfterMs}}""", ContentType.Application.Json, HttpStatusCode.TooManyRequests,
                    )
                }
            }
            // Real-time chat + presence. Clients connect, say hello (name), then exchange chat.
            webSocket("/ws") {
                val address = call.request.origin.remoteAddress // raw IP, no reverse-DNS
                val host = call.isHost()
                val id = chatHub.onConnect(this, address)
                try {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            when (val msg = chatHub.parseClient(frame.readText())) {
                                is Hello -> chatHub.onHello(id, msg.name, msg.deviceId)
                                is ChatSend -> chatHub.onChat(id, msg.text)
                                is ClearChat -> chatHub.onClearChat(id)
                                // Only the host goes live; other devices can only watch.
                                is LiveStart -> if (host) chatHub.onLiveStart(id, msg.kind)
                                is LiveStop -> chatHub.onLiveStop(id)
                                is LiveWatch -> chatHub.onLiveWatch(id, msg.id)
                                is LiveLeave -> chatHub.onLiveLeave(id, msg.id)
                                is SignalSend -> chatHub.onSignal(id, msg)
                                // Playing a shared video/audio file for everyone: host only, too.
                                is MediaPlay -> if (host) {
                                    fileStore.get(msg.fileId)?.first?.let { meta ->
                                        val kind = MediaTypes.forFile(meta.name, meta.contentType).substringBefore('/')
                                        if (kind == "video" || kind == "audio") chatHub.onMediaPlay(id, meta.id, "/files/${meta.id}", meta.name, kind)
                                    }
                                }
                                is MediaPlayUrl -> if (host) {
                                    MediaLink.parse(msg.url)?.let { chatHub.onMediaPlay(id, null, it.src, it.name, it.kind) }
                                }
                                is MediaState -> chatHub.onMediaState(id, msg.playing, msg.position, msg.sync)
                                is MediaStop -> chatHub.onMediaStop(id)
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
                    append("\"localName\":").append(localName?.let { "\"$it\"" } ?: "null").append(',')
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
            // File sharing: upload, list (newest first), download (by id).
            // Upload: the body is the file itself, streamed to disk; its details ride in the query
            // (?name=&from=&device=, plus &path=&batch= for a dropped folder's files). Not multipart:
            // Ktor reads multipart text fields whole into memory, under the same limit as the file.
            post("/files") {
                // Refuse up front when the declared body can't fit. The store also enforces the
                // limits while streaming, since the declared size can't be trusted.
                val declared = call.request.contentLength()
                if (declared != null && declared > fileStore.room()) {
                    return@post call.respondText(
                        json.encodeToString(fileStore.usage()), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge,
                    )
                }
                val q = call.request.queryParameters
                val from = q["from"]?.take(40)?.ifBlank { null } ?: "someone"
                // The uploader's device, as the roster knows it, so each dashboard can tell its own files.
                val fromId = q["device"]?.let { peerIdFor("", it, call.request.origin.remoteAddress) }?.ifEmpty { null }
                val body = call.receiveChannel()
                val meta = try {
                    // Blocking stream + disk writes: off the request threads, or a few slow uploads
                    // would stall every other request until they finish.
                    withContext(Dispatchers.IO) {
                        body.toInputStream().use {
                            fileStore.save(
                                q["name"] ?: "file", call.request.headers[HttpHeaders.ContentType], from, it,
                                q["path"]?.take(2000), q["batch"]?.take(64), fromId,
                            )
                        }
                    }
                } catch (e: FileStore.StorageFullException) {
                    return@post call.respondText(
                        json.encodeToString(e.usage), ContentType.Application.Json, HttpStatusCode.PayloadTooLarge,
                    )
                }
                chatHub.announceFile(meta)
                call.respondText(json.encodeToString(listOf(meta)), ContentType.Application.Json)
            }
            get("/files") {
                call.respondText(json.encodeToString(fileStore.list()), ContentType.Application.Json)
            }
            // Space used / left and the auto-clear age, shown under the drop zone.
            get("/files/usage") {
                call.respondText(json.encodeToString(fileStore.usage()), ContentType.Application.Json)
            }
            // Every shared file in one zip, or with ?batch= one dropped folder, streamed (nothing
            // buffered in memory). Folder files keep their folder paths; duplicate names get a
            // " (2)" suffix so no entry overwrites another when extracted.
            get("/files/zip") {
                val batch = call.request.queryParameters["batch"]
                val entries = if (batch != null) fileStore.batchEntries(batch) else fileStore.entries()
                if (entries.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)
                val zipName = if (batch != null) entries.first().first.path!!.substringBefore('/') + ".zip" else "servd-files.zip"
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, zipName)
                        .toString(),
                )
                call.respondOutputStream(ContentType.Application.Zip) {
                    writeZip(entries.asSequence().map { (meta, file) -> (meta.path ?: meta.name) to file })
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
                // A safe media type so the chat can preview images/video/audio/text; opened
                // directly it still downloads (attachment + nosniff). An id's bytes never change.
                call.response.header("X-Content-Type-Options", "nosniff")
                call.response.header(HttpHeaders.CacheControl, "private, max-age=86400, immutable")
                call.respond(LocalFileContent(file, ContentType.parse(MediaTypes.forFile(meta.name, meta.contentType))))
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
            // Delete one dropped folder (all its files) in one go.
            delete("/files/batch/{batch}") {
                val removed = call.parameters["batch"]?.let { fileStore.removeBatch(it) }.orEmpty()
                if (removed.isEmpty()) return@delete call.respond(HttpStatusCode.NotFound)
                chatHub.announceFilesRemoved(removed.map { it.id })
                call.respond(HttpStatusCode.OK)
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
            // A folder (recursively) as one streamed zip. Unreadable files are skipped rather
            // than failing the whole download.
            get("/fs/zip") {
                if (!browser.enabled) return@get call.respond(HttpStatusCode.NotFound)
                val (dir, files) = browser.filesUnder(call.request.queryParameters["path"] ?: "")
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, dir.name.ifBlank { "servd-share" } + ".zip")
                        .toString(),
                )
                call.respondOutputStream(ContentType.Application.Zip) { writeZip(files) }
            }
            // Upload one file into a browse folder: the body is the file (?path=folder&name=file).
            post("/fs/upload") {
                if (!browser.enabled) return@post call.respond(HttpStatusCode.NotFound)
                if (!browser.writable) return@post call.respond(HttpStatusCode.Forbidden)
                val q = call.request.queryParameters
                val body = call.receiveChannel()
                val ok = withContext(Dispatchers.IO) { // blocking, see POST /files
                    body.toInputStream().use { browser.upload(q["path"] ?: "", q["name"] ?: "file", it) }
                }
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
            }

            // Host-only admin API: reachable from loopback (the host machine) only, so LAN
            // devices can use the dashboard but cannot reconfigure the server.
            get("/admin/browse") {
                call.respondText(json.encodeToString(browser.config()), ContentType.Application.Json)
            }
            post("/admin/browse") {
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
                call.respondText(
                    json.encodeToString(browser.serverDirs(call.request.queryParameters["path"])),
                    ContentType.Application.Json,
                )
            }
            // Join PIN, host-only. Setting, changing or clearing it signs every other device out.
            get("/admin/pin") {
                call.respondText(pinConfig(), ContentType.Application.Json)
            }
            post("/admin/pin") {
                val pin = call.receiveParameters()["pin"]?.trim().orEmpty().ifEmpty { null }
                if (pin != null && !JoinPin.isValid(pin)) {
                    return@post call.respondText(pinConfig(), ContentType.Application.Json, HttpStatusCode.BadRequest)
                }
                joinPin.set(pin)
                if (pin != null) chatHub.closeWhere { !isLoopback(it) } // drop now-unauthorised chats
                call.respondText(pinConfig(), ContentType.Application.Json)
            }
            // Drop-files limits, host-only: optional quota (bytes) and auto-clear age (hours).
            get("/admin/storage") {
                call.respondText(json.encodeToString(fileStore.usage()), ContentType.Application.Json)
            }
            post("/admin/storage") {
                val p = call.receiveParameters()
                val ok = runCatching {
                    val maxBytes = p["maxBytes"]?.trim().orEmpty().ifEmpty { null }?.toLong()
                    val expireHours = p["expireHours"]?.trim().orEmpty().ifEmpty { null }?.toInt()
                    fileStore.limits.set(maxBytes, expireHours)
                }.isSuccess
                if (ok) expireFiles() // a newly shorter auto-clear applies right away
                call.respondText(
                    json.encodeToString(fileStore.usage()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            // Reverse proxy (re-serve a host-run web app to the LAN), host-only.
            get("/admin/proxy") {
                call.respondText(json.encodeToString(webProxy.config()), ContentType.Application.Json)
            }
            // Scan-to-open QR of the served site's URL (404 while nothing is served).
            get("/admin/proxy/qr") {
                val siteUrl = webProxy.config().url ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondText(Qr.svg("$siteUrl/"), ContentType("image", "svg+xml"))
            }
            post("/admin/proxy") {
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
                call.respondText(json.encodeToString(serviceManager.list()), ContentType.Application.Json)
            }
            post("/admin/services/{id}/start") {
                val ok = call.parameters["id"]?.let { serviceManager.start(it) } ?: false
                call.respondText(
                    json.encodeToString(serviceManager.list()),
                    ContentType.Application.Json,
                    status = if (ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
                )
            }
            post("/admin/services/{id}/stop") {
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
                // The app/CLI opens the host's dashboard as /?admin=<token>: keep the token as a
                // cookie (HttpOnly, never sent cross-site) and drop it from the address bar.
                val token = call.request.queryParameters["admin"]
                if (token != null) {
                    if (call.isLoopbackClient() && MessageDigest.isEqual(token.toByteArray(), adminToken.toByteArray())) {
                        call.response.header( // by hand, like the PIN cookie
                            HttpHeaders.SetCookie,
                            "$ADMIN_COOKIE=$adminToken; Max-Age=${400 * 24 * 3600}; Path=/; Secure; HttpOnly; SameSite=Strict",
                        )
                    }
                    return@get call.respondRedirect("/")
                }
                val html = indexHtml
                if (html != null) call.respondBytes(html, ContentType.Text.Html)
                else call.respond(HttpStatusCode.NotFound)
            }
        }
    }

    /** Delete files past the auto-clear age and tell every dashboard. */
    private suspend fun expireFiles() {
        chatHub.announceFilesRemoved(fileStore.expire().map { it.id })
    }

    private fun pinConfig(): String =
        """{"enabled":${joinPin.enabled},"pin":${joinPin.current?.let { "\"$it\"" } ?: "null"}}"""
}

/** Reachable without a session while a join PIN is set: the page (it shows the PIN screen) and the check. */
private val PIN_OPEN_PATHS = setOf("/", "/auth")

private const val ADMIN_COOKIE = "servd_admin"
private const val MAX_WS_FRAME = 1L shl 20 // 1 MB

/**
 * Who may reach [path]; null = go ahead, else the status to refuse with. The admin API needs the
 * [host] (loopback plus the admin token). Everything else needs this machine ([loopback]) or a
 * PIN [session] (always true while no PIN is set), except the page and the PIN check.
 */
internal fun accessDenied(path: String, loopback: Boolean, session: Boolean, host: Boolean): HttpStatusCode? = when {
    (path == "/admin" || path.startsWith("/admin/")) && !host -> HttpStatusCode.Forbidden
    path !in PIN_OPEN_PATHS && !loopback && !session -> HttpStatusCode.Unauthorized
    else -> null
}

/**
 * Stream [entries] (zip path to file) as one zip. Unreadable files are skipped rather than failing
 * the whole download, and duplicate paths get a " (2)" suffix so nothing is overwritten on extract.
 *
 * Tuned for speed on a LAN, where CPU is the bottleneck, not bandwidth (it matters most on a phone
 * hub). Formats that are already compressed (photos, video, audio, archives) are stored rather than
 * deflated again: recompressing them burns CPU and makes them slightly bigger. Writes are buffered
 * in large blocks rather than the deflater's tiny ones.
 */
private fun OutputStream.writeZip(entries: Sequence<Pair<String, File>>) {
    ZipOutputStream(BufferedOutputStream(this, ZIP_BUFFER)).use { zip ->
        val used = HashSet<String>()
        for ((path, file) in entries) {
            val input = runCatching { file.inputStream() }.getOrNull() ?: continue
            input.use {
                zip.setLevel(if (isCompressed(path)) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(uniqueName(path, used)))
                it.copyTo(zip, ZIP_BUFFER)
                zip.closeEntry()
            }
        }
    }
}

private const val ZIP_BUFFER = 64 * 1024

/** Formats that are already compressed, so deflating them again gains nothing. */
private val COMPRESSED_EXT = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif",
    "mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp",
    "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac",
    "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "zst", "apk", "jar",
    "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "pdf",
)

private fun isCompressed(path: String): Boolean =
    path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in COMPRESSED_EXT

/**
 * [name], or "name (2).ext", "name (3).ext"… if already in [used]. Records the result in [used].
 * [name] may be a path ("photos/a.jpg"); only the last segment's extension counts.
 */
private fun uniqueName(name: String, used: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (!used.add(candidate.lowercase())) candidate = numberedName(name, n++)
    return candidate
}

/** True only when the request came from this machine (loopback), gating the admin API. */
private fun ApplicationCall.isLoopbackClient(): Boolean = isLoopback(request.origin.remoteAddress)

/** [address] is a raw IP (no DNS lookup happens) on this machine's loopback. */
private fun isLoopback(address: String): Boolean =
    runCatching { InetAddress.getByName(address).isLoopbackAddress }.getOrDefault(false)
