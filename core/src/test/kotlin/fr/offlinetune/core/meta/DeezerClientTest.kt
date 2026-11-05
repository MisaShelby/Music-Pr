package fr.offlinetune.core.meta

import fr.offlinetune.core.model.NotFound
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Tag("network")
class DeezerClientTest {
    private val deezer = DeezerClient()

    @Test fun `track returns full metadata`() = runBlocking {
        val t = deezer.track("3135556")
        assertEquals("Harder, Better, Faster, Stronger", t.title)
        assertEquals("Daft Punk", t.artist)
        assertEquals(2001, t.year)
        assertTrue(t.coverUrl!!.endsWith("1000x1000-000000-80-0-0.jpg"), t.coverUrl)
        assertNotNull(t.genre)
        assertNotNull(t.album)
        assertNotNull(t.isrc)
        assertTrue(t.durationSec!! in 220..230)
    }

    @Test fun `search finds daft punk`() = runBlocking {
        val hits = deezer.search("daft punk one more time")
        assertTrue(hits.isNotEmpty())
        assertEquals("Daft Punk", hits.first().artist)
        assertTrue(hits.first().url.startsWith("https://www.deezer.com/track/"))
    }

    @Test fun `unknown id throws NotFound`() {
        assertThrows<NotFound> { runBlocking { deezer.track("999999999999") } }
    }

    @Test fun `byIsrc finds the track`() = runBlocking {
        val t = deezer.byIsrc("GBARL9300135") // Never Gonna Give You Up
        assertNotNull(t)
        assertEquals("Rick Astley", t!!.artist)
    }
}
