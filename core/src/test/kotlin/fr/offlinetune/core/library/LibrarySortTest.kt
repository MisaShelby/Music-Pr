package fr.offlinetune.core.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibrarySortTest {
    private data class T(val id: String, val title: String, val artist: String, val track: Int?, val file: String, val dir: String, val added: Long, val plays: Int)

    private val list = listOf(
        T("a", "Été", "Zaz", 2, "b.mp3", "Z/Album", 300, 5),
        T("b", "abc", "Adele", 1, "a.mp3", "A/Album", 100, 9),
        T("c", "Zèbre", "adele", null, "c.mp3", "A/Album", 200, 0),
    )
    private fun fields(t: T) = SortFields(t.title, t.artist, t.track, t.file, t.dir, t.added, t.plays)
    private fun ids(k: SortKey, desc: Boolean) = LibrarySort.sort(list, k, desc, ::fields).map { it.id }

    @Test fun `default is most recently added first`() = assertEquals(listOf("a", "c", "b"), ids(SortKey.ADDED, desc = true))
    @Test fun `title ignores case and accents`() = assertEquals(listOf("b", "a", "c"), ids(SortKey.TITLE, desc = false))
    @Test fun `artist then title`() = assertEquals(listOf("b", "c", "a"), ids(SortKey.ARTIST, desc = false))
    @Test fun `track number, unknown last`() = assertEquals(listOf("b", "a", "c"), ids(SortKey.TRACK, desc = false))
    @Test fun `file name`() = assertEquals(listOf("b", "a", "c"), ids(SortKey.FILENAME, desc = false))
    @Test fun `location then file`() = assertEquals(listOf("b", "c", "a"), ids(SortKey.LOCATION, desc = false))
    @Test fun `most played first`() = assertEquals(listOf("b", "a", "c"), ids(SortKey.PLAYS, desc = true))

    @Test fun `queue starts at the touched track`() {
        assertEquals(listOf(2, 3, 4), LibrarySort.queueFrom(listOf(1, 2, 3, 4), 1))
        assertEquals(emptyList<Int>(), LibrarySort.queueFrom(listOf(1), 5))
    }

    @Test fun `selection keeps the order of selection`() {
        val all = listOf("x", "y", "z")
        assertEquals(listOf("z", "x"), LibrarySort.inSelectionOrder(listOf("z", "x", "gone"), all) { it })
    }
}
