package dev.servd.core.browse

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTest {

    /** A temp dir holding a shared `root/` (with nested files) and a sibling `outside/` secret. */
    private fun withTree(block: (root: File, outside: File) -> Unit) {
        val base = Files.createTempDirectory("servd-browse").toFile()
        try {
            val root = File(base, "root").apply { mkdirs() }
            File(root, "a.txt").writeText("a")
            File(root, "sub/deep").mkdirs()
            File(root, "sub/b.txt").writeText("b")
            File(root, "sub/deep/c.txt").writeText("c")
            val outside = File(base, "outside").apply { mkdirs() }
            File(outside, "secret.txt").writeText("secret")
            block(root, outside)
        } finally {
            base.deleteRecursively()
        }
    }

    private fun zipPaths(b: Browser, rel: String) = b.filesUnder(rel)!!.second.map { it.first }.sorted().toList()

    @Test
    fun filesUnder_lists_nested_files_relative_to_the_folder() = withTree { root, _ ->
        File(root, "sub/.servd-upload-123.part").writeText("half") // an upload still in progress
        val b = Browser().apply { enable(root.path, writable = false) }
        assertEquals(listOf("a.txt", "sub/b.txt", "sub/deep/c.txt"), zipPaths(b, ""))
        assertEquals(listOf("b.txt", "deep/c.txt"), zipPaths(b, "sub"))
        assertEquals("sub", b.filesUnder("sub")!!.first.name)
    }

    @Test
    fun paths_escaping_the_root_are_refused() = withTree { root, _ ->
        val b = Browser().apply { enable(root.path, writable = false) }
        for (rel in listOf("..", "../outside", "sub/../../outside", "/../outside")) {
            assertNull(b.filesUnder(rel), "filesUnder($rel)")
            assertNull(b.list(rel), "list($rel)")
        }
        assertNull(b.download("../outside/secret.txt"))
        assertFalse(b.upload("..", "x.txt", "x".byteInputStream()), "upload is refused when read-only")
    }

    @Test
    fun filesUnder_is_null_for_a_file_or_when_disabled() = withTree { root, _ ->
        val b = Browser().apply { enable(root.path, writable = false) }
        assertNull(b.filesUnder("a.txt"))
        assertNull(b.filesUnder("missing"))
        b.disable()
        assertNull(b.filesUnder(""))
    }

    @Test
    fun a_drive_or_filesystem_root_can_be_shared() = withTree { root, _ ->
        val drive = root.canonicalFile.toPath().root.toFile() // "C:\" or "/": its path ends in a separator
        val b = Browser().apply { enable(drive.path, writable = false) }
        val rel = root.canonicalPath.removePrefix(drive.path)
        assertEquals(listOf("sub", "a.txt"), b.list(rel)?.entries?.map { it.name })
        assertEquals("a", b.download("$rel/a.txt")?.readText())
    }

    @Test
    fun uploads_never_replace_a_file_and_a_failed_one_leaves_nothing() = withTree { root, _ ->
        val b = Browser().apply { enable(root.path, writable = true) }
        assertTrue(b.upload("", "a.txt", "new".byteInputStream()))
        assertTrue(b.upload("", "a.txt", "newer".byteInputStream()))
        assertEquals("a", File(root, "a.txt").readText(), "the host's file is untouched")
        assertEquals("new", File(root, "a (2).txt").readText())
        assertEquals("newer", File(root, "a (3).txt").readText())

        val broken = object : java.io.InputStream() {
            var sent = 0
            override fun read(): Int = if (sent++ < 10) 'x'.code else throw java.io.IOException("connection lost")
        }
        assertFailsWith<java.io.IOException> { b.upload("sub", "cut.txt", broken) }
        assertEquals(listOf("b.txt", "deep"), File(root, "sub").list()!!.sorted(), "no partial or temp file left")
    }

    @Test
    fun filesUnder_skips_links_that_lead_outside_the_root() = withTree { root, outside ->
        // Creating symlinks needs a privilege on Windows; skip the check where that's unavailable.
        val linked = runCatching { Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath()) }.isSuccess
        if (!linked) return@withTree
        val b = Browser().apply { enable(root.path, writable = false) }
        assertEquals(listOf("a.txt", "sub/b.txt", "sub/deep/c.txt"), zipPaths(b, ""))
    }
}
