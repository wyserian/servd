package dev.servd.core.files

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderDropTest {

    private fun withStore(block: (FileStore) -> Unit) {
        val dir = Files.createTempDirectory("servd-folder").toFile()
        try { block(FileStore(dir)) } finally { dir.deleteRecursively() }
    }

    private fun bytes(s: String) = s.byteInputStream()

    @Test
    fun paths_are_made_safe_for_display_and_zip_entries() {
        assertEquals("photos/2024/a.jpg", FileStore.sanitizePath("photos/2024/a.jpg"))
        assertEquals("photos/a.jpg", FileStore.sanitizePath("photos\\a.jpg"))
        // Nothing can climb out of an extracted zip, or be absolute.
        assertEquals("photos/etc/passwd", FileStore.sanitizePath("photos/../../etc/passwd"))
        assertEquals("photos/a.jpg", FileStore.sanitizePath("/photos/./a.jpg"))
        assertEquals("C_/x/a.jpg", FileStore.sanitizePath("C:/x/a.jpg"))
        assertEquals("photos/a_b_.jpg", FileStore.sanitizePath("photos/a<b>.jpg"))
        // A path must be at least folder/file.
        assertNull(FileStore.sanitizePath("a.jpg"))
        assertNull(FileStore.sanitizePath("../a.jpg"))
        assertNull(FileStore.sanitizePath(""))
        assertNull(FileStore.sanitizePath(null))
    }

    @Test
    fun folder_files_keep_path_and_batch_and_loose_files_do_not() = withStore { store ->
        val a = store.save("a.jpg", null, "x", bytes("a"), path = "photos/2024/a.jpg", batch = "batch0001")
        assertEquals("a.jpg", a.name)
        assertEquals("photos/2024/a.jpg", a.path)
        assertEquals("batch0001", a.batch)

        val loose = store.save("b.txt", null, "x", bytes("b"))
        assertNull(loose.path)
        assertNull(loose.batch)
    }

    @Test
    fun bad_batch_or_path_falls_back_to_a_loose_file() = withStore { store ->
        val badBatch = store.save("a.jpg", null, "x", bytes("a"), path = "photos/a.jpg", batch = "../evil")
        assertNull(badBatch.batch)
        assertNull(badBatch.path)
        val noFolder = store.save("a.jpg", null, "x", bytes("a"), path = "a.jpg", batch = "batch0001")
        assertNull(noFolder.batch)
        assertNull(noFolder.path)
    }

    @Test
    fun a_folder_is_listed_and_removed_as_one_batch() = withStore { store ->
        store.save("a.jpg", null, "x", bytes("a"), path = "photos/a.jpg", batch = "batch0001")
        store.save("b.jpg", null, "x", bytes("bb"), path = "photos/sub/b.jpg", batch = "batch0001")
        store.save("c.txt", null, "x", bytes("c"), path = "docs/c.txt", batch = "batch0002")
        store.save("d.txt", null, "x", bytes("d"))

        assertEquals(setOf("photos/a.jpg", "photos/sub/b.jpg"), store.batchEntries("batch0001").map { it.first.path }.toSet())
        assertTrue(store.batchEntries("nope-nope").isEmpty())

        val removed = store.removeBatch("batch0001")
        assertEquals(2, removed.size)
        assertEquals(setOf("c.txt", "d.txt"), store.list().map { it.name }.toSet())
        assertEquals(2, store.usage().usedBytes, "the folder's bytes were freed")
    }
}
