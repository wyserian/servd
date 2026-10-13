package dev.servd.core.browse

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
    fun filesUnder_skips_links_that_lead_outside_the_root() = withTree { root, outside ->
        // Creating symlinks needs a privilege on Windows; skip the check where that's unavailable.
        val linked = runCatching { Files.createSymbolicLink(File(root, "escape").toPath(), outside.toPath()) }.isSuccess
        if (!linked) return@withTree
        val b = Browser().apply { enable(root.path, writable = false) }
        assertEquals(listOf("a.txt", "sub/b.txt", "sub/deep/c.txt"), zipPaths(b, ""))
    }
}
