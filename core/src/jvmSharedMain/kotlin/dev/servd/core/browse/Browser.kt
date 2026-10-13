package dev.servd.core.browse

import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream

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
        if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) return null
        return target
    }

    /** Folders + files directly under [rel] (relative to the root); null if not a directory. */
    fun list(rel: String): Listing? {
        val dir = resolve(rel) ?: return null
        if (!dir.isDirectory) return null
        val kids = dir.listFiles() ?: emptyArray()
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
        fun inside(f: File) = f.path == base.path || f.path.startsWith(base.path + File.separator)
        val seen = HashSet<String>()
        val files = dir.walkTopDown()
            .onEnter { d -> d.canonicalFile.let { inside(it) && seen.add(it.path) } }
            .filter { it.isFile && inside(it.canonicalFile) }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
        return dir to files
    }

    /** Save [input] as [name] inside directory [rel]. Returns false if not writable / bad path. */
    fun upload(rel: String, name: String, input: InputStream): Boolean {
        val base = rootDir ?: return false
        if (!writeEnabled) return false
        val dir = resolve(rel)?.takeIf { it.isDirectory } ?: return false
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        val target = File(dir, safe).canonicalFile
        if (!target.path.startsWith(base.path + File.separator)) return false
        target.outputStream().use { input.copyTo(it) }
        return true
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
