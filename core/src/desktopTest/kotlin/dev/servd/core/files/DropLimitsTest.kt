package dev.servd.core.files

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropLimitsTest {

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("servd-drop").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private val plentyOfDisk = { 100L shl 30 } // 100 GB free
    private val noTotal = { 0L }               // floor = the 1 GB minimum

    private fun bytes(n: Int) = ByteArray(n) { 'x'.code.toByte() }.inputStream()

    /** [size] bytes, calling [onRead] with the running total as they're read. */
    private fun stream(size: Long, onRead: (Long) -> Unit) = object : InputStream() {
        var pos = 0L
        override fun read(): Int = if (pos >= size) -1 else { pos++; onRead(pos); 'x'.code }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            pos += n; onRead(pos)
            return n
        }
    }

    private fun idFiles(dir: File) = dir.listFiles()!!.map { it.name }.filter { it.length == 36 }

    @Test
    fun quota_is_enforced_while_streaming_and_partial_files_are_removed() = withDir { dir ->
        val store = FileStore(dir, DropLimits(null).apply { set(100, null) }, plentyOfDisk, noTotal)
        store.save("a.txt", null, "x", bytes(60))
        assertEquals(40, store.usage().roomBytes)
        val full = assertFailsWith<FileStore.StorageFullException> { store.save("b.txt", null, "x", bytes(60)) }
        assertEquals(40, full.usage.roomBytes, "reports the real room, not the room mid-upload")
        assertEquals(1, store.list().size)
        assertEquals(1, idFiles(dir).size, "the rejected upload left nothing on disk")
        assertEquals(60, store.usage().usedBytes)
        store.save("c.txt", null, "x", bytes(40)) // exactly fills the quota
        assertEquals(0, store.usage().roomBytes)
        assertFailsWith<FileStore.StorageFullException> { store.save("d.txt", null, "x", bytes(1)) }
    }

    @Test
    fun removing_files_frees_quota() = withDir { dir ->
        val store = FileStore(dir, DropLimits(null).apply { set(100, null) }, plentyOfDisk, noTotal)
        val a = store.save("a.txt", null, "x", bytes(100))
        assertEquals(0, store.usage().roomBytes)
        store.remove(a.id)
        assertEquals(100, store.usage().roomBytes)
        store.save("b.txt", null, "x", bytes(70))
        store.clear()
        assertEquals(0, store.usage().usedBytes)
    }

    @Test
    fun the_free_space_floor_applies_even_without_a_quota() = withDir { dir ->
        val floor = FileStore.MIN_FREE_BYTES
        val full = FileStore(dir, DropLimits(null), usableSpace = { floor }, totalSpace = noTotal)
        assertEquals(0, full.room())
        assertFailsWith<FileStore.StorageFullException> { full.save("a.txt", null, "x", bytes(1)) }

        // 10 MB above the floor: a 20 MB upload is cut off mid-stream, and nothing is kept.
        var read = 0L
        val store = FileStore(dir, DropLimits(null), usableSpace = { floor + (10L shl 20) - read }, totalSpace = noTotal)
        assertFailsWith<FileStore.StorageFullException> {
            store.save("big.bin", null, "x", stream(20L shl 20) { read = it })
        }
        assertTrue(read < (20L shl 20), "stopped before the end")
        assertTrue(idFiles(dir).isEmpty())
    }

    @Test
    fun floor_is_5_percent_of_a_large_disk() = withDir { dir ->
        val store = FileStore(dir, DropLimits(null), plentyOfDisk, totalSpace = { 1000L shl 30 })
        assertEquals(50L shl 30, store.freeFloor())
    }

    @Test
    fun auto_clear_removes_only_old_files() = withDir { dir ->
        val store = FileStore(dir, DropLimits(null), plentyOfDisk, noTotal)
        val a = store.save("a.txt", null, "x", bytes(5))
        assertTrue(store.expire(a.ts + 10 * 3_600_000L).isEmpty(), "auto-clear off: nothing expires")

        store.limits.set(null, 1)
        assertTrue(store.expire(a.ts + 30 * 60_000L).isEmpty(), "30 min old: kept")
        assertEquals(listOf(a.id), store.expire(a.ts + 61 * 60_000L).map { it.id })
        assertTrue(store.list().isEmpty())
        assertTrue(idFiles(dir).isEmpty())
    }

    @Test
    fun startup_removes_last_runs_files_but_not_sftp_uploads() = withDir { dir ->
        File(dir, "3f2504e0-4f89-11d3-9a0c-0305e82c3301").writeText("orphan from last run")
        File(dir, "notes.txt").writeText("uploaded over SFTP")
        FileStore(dir)
        assertEquals(listOf("notes.txt"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun limits_persist_and_validate() = withDir { dir ->
        val file = File(dir, "storage.properties")
        DropLimits(file).set(10L shl 30, 24)
        DropLimits(file).let {
            assertEquals(10L shl 30, it.maxBytes)
            assertEquals(24, it.expireHours)
        }
        assertFailsWith<IllegalArgumentException> { DropLimits(file).set(0, null) }
        assertFailsWith<IllegalArgumentException> { DropLimits(file).set(null, 5) }
        DropLimits(file).set(null, null)
        assertFalse(file.exists())
        DropLimits(file).let {
            assertNull(it.maxBytes)
            assertNull(it.expireHours)
        }
    }
}
