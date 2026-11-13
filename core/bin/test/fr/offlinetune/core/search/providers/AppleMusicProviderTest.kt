package fr.offlinetune.core.search.providers

import fr.offlinetune.core.search.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class AppleMusicProviderTest {
    @Test fun `parses itunes search results`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"resultCount":1,"results":[{"trackName":"One More Time","artistName":"Daft Punk",
                "collectionName":"Discovery","trackTimeMillis":320357,"artworkUrl100":"https://x/100x100bb.jpg",
                "trackViewUrl":"https://music.apple.com/fr/album/x?i=1"}]}"""))
            val hits = AppleMusicProvider(base = s.url("").toString().trimEnd('/')).search("daft punk", 5)
            val h = hits.single()
            assertEquals(Provider.APPLE_MUSIC, h.provider)
            assertEquals("One More Time", h.title); assertEquals("Daft Punk", h.artist); assertEquals("Discovery", h.album)
            assertEquals(320, h.durationSec)
            assertEquals("https://x/1000x1000bb.jpg", h.coverUrl)
            assertEquals("https://music.apple.com/fr/album/x?i=1", h.url)
            assertTrue(h.quality.isNotBlank())
            val req = s.takeRequest().path!!
            assertTrue("country=FR" in req && "entity=song" in req && "limit=5" in req, req)
        }
    }

    @Tag("network")
    @Test fun `real search`() = runBlocking {
        val h = AppleMusicProvider().search("daft punk one more time").first()
        assertEquals("Daft Punk", h.artist)
        assertTrue(h.coverUrl!!.contains("1000x1000"))
    }
}
