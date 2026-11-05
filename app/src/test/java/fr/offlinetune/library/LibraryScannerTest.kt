package fr.offlinetune.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LibraryScannerTest {
    private class Node(
        override val name: String,
        private val kids: List<Node>? = null,
        override val lastModified: Long = 1,
        private val failing: Boolean = false,
    ) : TreeNode {
        override val uri get() = "u:$name"
        override val isDir get() = kids != null
        override fun children(): List<TreeNode> = if (failing) throw SecurityException("revoked") else kids ?: emptyList()
    }

    private fun tags(uri: String) = TagData(title = "T:$uri", artist = "A", album = "Al", albumArtist = null, year = 2001, trackNumber = 1, durationMs = 1000)

    private val tree = Node(
        "root",
        listOf(
            Node("Daft Punk - One More Time", listOf(
                Node("Daft Punk - One More Time.opus"), Node("Daft Punk - One More Time.lrc"), Node("cover.jpg"), Node("notes.txt"),
            )),
            Node("B", listOf(Node("SONG.MP3"))),
            Node("c.flac"),
            Node(".hidden.part", listOf(Node("y.opus"))),
        ),
    )

    @Test fun `finds audio files and links lrc and cover`() {
        val tracks = LibraryScanner(::tags).scan(tree, emptyMap())
        assertEquals(setOf("u:Daft Punk - One More Time.opus", "u:SONG.MP3", "u:c.flac"), tracks.map { it.uri }.toSet())
        val opus = tracks.first { it.uri.endsWith(".opus") }
        assertEquals("u:Daft Punk - One More Time.lrc", opus.lrcUri)
        assertEquals("u:cover.jpg", opus.coverUri)
        assertNull(tracks.first { it.uri == "u:c.flac" }.lrcUri)
    }

    @Test fun `unreadable tags fall back to the file name`() {
        val tracks = LibraryScanner { uri -> if (uri.endsWith(".opus")) throw RuntimeException("corrupt") else tags(uri) }.scan(tree, emptyMap())
        val opus = tracks.first { it.uri.endsWith(".opus") }
        assertEquals("One More Time", opus.title)
        assertEquals("Daft Punk", opus.artist)
    }

    @Test fun `empty tags fall back to the file name`() {
        val tracks = LibraryScanner { TagData(null, null, null, null, null, null, null) }.scan(tree, emptyMap())
        assertEquals("c", tracks.first { it.uri == "u:c.flac" }.title)
        assertEquals("Artiste inconnu", tracks.first { it.uri == "u:c.flac" }.artist)
    }

    @Test fun `a revoked folder does not stop the scan`() {
        val t = Node("root", listOf(Node("locked", failing = true), Node("ok.mp3")))
        assertEquals(listOf("u:ok.mp3"), LibraryScanner(::tags).scan(t, emptyMap()).map { it.uri })
    }

    @Test fun `unchanged files reuse the previous entry`() {
        var calls = 0
        val scanner = LibraryScanner { calls++; tags(it) }
        val first = scanner.scan(tree, emptyMap())
        assertEquals(3, calls)
        scanner.scan(tree, first.associateBy { it.uri })
        assertEquals(3, calls)
    }
}
