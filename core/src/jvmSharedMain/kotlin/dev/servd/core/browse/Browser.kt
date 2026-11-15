package dev.servd.core.browse

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * Serves a host-chosen directory (the "browse root") to LAN clients: list, download, and - when
 * writable - upload. Every client path is relative to the root and canonically jailed inside it,
 * so a crafted path can never escape. Off until a root is set by the host (loopback admin only).
 *
 * Backed by java.io.File - covers desktop and Android all-files-access. A SAF-backed source for
 * the Android folder picker is a later phase.
 */
class Browser {
    @Volatile private var rootDir: File? = null
    @Volatile private var writeEnabled: Boolean = false

    /**
     * Starting folders for the host's folder picker (blank path). Empty = the filesystem roots
     * (drive letters on Windows, "/" on Unix). Android sets this to the phone's storage dirs,
     * which are the useful place to start (the true root "/" is mostly unreadable).
     */
    @Volatile var pickerRoots: List<PickerDir> = emptyList()

    val enabled: Boolean get() = rootDir != null
    val writable: Boolean get() = writeEnabled && rootDir != null
    val rootPath: String? get() = rootDir?.absolutePath
    val rootName: String? get() = rootDir?.let { it.name.ifBlank { it.absolutePath } }

    @Serializable
    data class Entry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

    @Serializable
    data class Listing(val path: String, val entries: List<Entry>)

    @Serializable
    data class PickerDir(val name: String, val path: String)

    @Serializable
    data class PickerListing(val current: String, val parent: String?, val dirs: List<PickerDir>)

    /** What clients may know: whether browsing is on, whether they can upload, and the root's name. */
    @Serializable
    data class Info(val enabled: Boolean, val writable: Boolean, val root: String?)

    /** Host-only config (includes the absolute root path). */
    @Serializable
    data class Config(val enabled: Boolean, val writable: Boolean, val rootPath: String?)

    fun info() = Info(enabled, writable, rootName)
    fun config() = Config(enabled, writable, rootPath)

    /** Turn browsing on with [path] as the root. Throws if it is not a directory. */
    fun enable(path: String, writable: Boolean) {
        val f = File(path).canonicalFile
        require(f.isDirectory) { "not a directory: $path" }
        rootDir = f
        writeEnabled = writable
    }

    fun disable() {
        rootDir = null
        writeEnabled = false
    }

    /** Resolve a client-relative path inside the root, or null if it escapes (or root unset). */
    private fun resolve(rel: String): File? {
        val base = rootDir ?: return null
        val clean = rel.trim().trimStart('/', '\\')
        val target = (if (clean.isEmpty()) base else File(base, clean)).canonicalFile
        return target.takeIf { base.holds(it) }
    }

    /** [f] (canonical) is this folder or inside it. A drive or filesystem root already ends in a separator. */
    private fun File.holds(f: File): Boolean =
        f.path == path || f.path.startsWith(path.removeSuffix(File.separator) + File.separator)

    /** Folders + files directly under [rel] (relative to the root); null if not a directory. */
    fun list(rel: String): Listing? {
        val dir = resolve(rel) ?: return null
        if (!dir.isDirectory) return null
        val kids = dir.listFiles()?.filterNot { it.name.startsWith(UPLOAD_PREFIX) } ?: emptyList() // uploads in progress
        val entries = kids.map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified()) }
            .sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
        return Listing(path = normalize(rel), entries = entries)
    }

    /** The backing file for a download, or null if it is not a readable file inside the root. */
    fun download(rel: String): File? = resolve(rel)?.takeIf { it.isFile }

    /**
     * Every file under directory [rel], recursively, paired with its path relative to that
     * directory (for a zip). Null if [rel] is not a directory. Links leading outside the root,
     * or back into a folder already walked, are skipped. Lazy, so huge trees stream.
     */
    fun filesUnder(rel: String): Pair<File, Sequence<Pair<String, File>>>? {
        val base = rootDir ?: return null
        val dir = resolve(rel)?.takeIf { it.isDirectory } ?: return null
        val seen = HashSet<String>()
        val files = dir.walkTopDown()
            .onEnter { d -> d.canonicalFile.let { base.holds(it) && seen.add(it.path) } }
            .filter { it.isFile && base.holds(it.canonicalFile) }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
        return dir to files
    }

    /**
     * Save [input] as [name] inside directory [rel]. Never replaces a host file: a taken name gets
     * " (2)", " (3)"…, and the bytes land under the final name only once complete, so a cut-off
     * upload leaves nothing behind. Returns false if not writable / bad path.
     */
    fun upload(rel: String, name: String, input: InputStream): Boolean {
        if (rootDir == null || !writeEnabled) return false
        val dir = resolve(rel)?.takeIf { it.isDirectory } ?: return false
        val safe = name.substringAfterLast('/').substringAfterLast('\\').trim()
            .takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"
        val tmp = File.createTempFile(UPLOAD_PREFIX, ".part", dir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            var n = 1
            while (true) {
                val target = File(dir, if (n == 1) safe else numberedName(safe, n))
                try {
                    Files.move(tmp.toPath(), target.toPath()) // no REPLACE_EXISTING: fails rather than overwrite
                    return true
                } catch (_: FileAlreadyExistsException) {
                    n++
                }
            }
        } finally {
            tmp.delete() // only still there if the upload failed
        }
    }

    /** Host-only folder picker: directories under [path] (drive/filesystem roots if blank). */
    fun serverDirs(path: String?): PickerListing {
        if (path.isNullOrBlank()) {
            val roots = pickerRoots.ifEmpty { File.listRoots().map { PickerDir(it.absolutePath, it.absolutePath) } }
            return PickerListing(current = "", parent = null, dirs = roots)
        }
        val here = File(path).canonicalFile
        val dirs = (here.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .sortedBy { it.name.lowercase() }
            .map { PickerDir(it.name, it.absolutePath) }
        return PickerListing(current = here.absolutePath, parent = here.parentFile?.absolutePath, dirs = dirs)
    }

    private fun normalize(rel: String): String = rel.trim().trim('/', '\\').replace('\\', '/')
}

private const val UPLOAD_PREFIX = ".servd-upload-"

/** "name (n).ext" for [name], which may be a path ("photos/a.jpg"); only the last segment's extension counts. */
internal fun numberedName(name: String, n: Int): String {
    val dot = name.lastIndexOf('.').takeIf { it > name.lastIndexOf('/') + 1 } ?: name.length
    return name.substring(0, dot) + " ($n)" + name.substring(dot)
}
