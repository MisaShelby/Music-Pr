package fr.offlinetune.core.meta

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class SpotifyEmbedClientTest {
    @Tag("network")
    @Test fun `track is read from embed page and enriched by deezer`() = runBlocking {
        val t = SpotifyEmbedClient(DeezerClient()).track("4uLU6hMCjMI75M1A2tKUQC")
        assertTrue(t.title.contains("Never Gonna Give You Up"), t.title)
        assertEquals("Rick Astley", t.artist)
        assertTrue(t.durationSec!! in 210..216, "${t.durationSec}")
        assertEquals(1987, t.year)
        assertNotNull(t.album)
        assertNotNull(t.coverUrl)
    }

    @Test fun `entity parsing from next data`() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":
            {"name":"Titre","artists":[{"name":"A"},{"name":"B"}],"duration":200500,"releaseDate":{"isoString":"2020-01-02T00:00:00Z"},
            "visualIdentity":{"image":[{"url":"small","maxWidth":64},{"url":"big","maxWidth":640}]}}}}}}}</script></html>"""
        val t = SpotifyEmbedClient.parseEmbed(html)!!
        assertEquals("Titre", t.title)
        assertEquals("A, B", t.artist)
        assertEquals(201, t.durationSec)
        assertEquals(2020, t.year)
        assertEquals("big", t.coverUrl)
    }

    @Test fun `unparseable page returns null`() {
        assertEquals(null, SpotifyEmbedClient.parseEmbed("<html>nothing</html>"))
    }
}
