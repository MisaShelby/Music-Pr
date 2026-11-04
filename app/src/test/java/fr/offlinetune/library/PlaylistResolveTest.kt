package fr.offlinetune.library

import fr.offlinetune.playlist.PlaylistResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlaylistResolveTest {
    private class Node(override val name: String, private val kids: List<Node>? = null) : TreeNode {
        override val uri get() = "u:$name"
        override val isDir get() = kids != null
        override val lastModified = 1L
        override fun children(): List<TreeNode> = kids ?: emptyList()
    }

    private val tree = Node("root", listOf(
        Node("A - B", listOf(Node("A - B.opus"))),
        Node("Playlists", listOf(Node("Mix.m3u8"))),
        Node("old.m3u"),
    ))

    @Test fun `scan finds tracks with their paths and playlist files`() {
        val r = LibraryScanner { null }.scanAll(tree, emptyMap())
        assertEquals(listOf("A - B", "A - B.opus"), r.tracks.single().pathSegments)
        assertEquals(setOf("Mix" to listOf("Playlists"), "old" to emptyList<String>()), r.playlists.map { it.name to it.dirSegments }.toSet())
    }

    @Test fun `entries resolve to library tracks, missing ones stay as null`() {
        val r = LibraryScanner { null }.scanAll(tree, emptyMap())
        val file = r.playlists.first { it.name == "Mix" }
        val text = "#EXTM3U\n#EXTINF:200,A - B\n../A - B/A - B.opus\n#EXTINF:100,Gone\n../Gone/Gone.mp3\n"
        val p = PlaylistResolver.resolve(file, text, r.tracks)
        assertEquals(2, p.items.size)
        assertEquals("u:A - B.opus", p.items[0].track!!.uri)
        assertNull(p.items[1].track)
        assertEquals("Gone", p.items[1].entry.label)
    }
}
