package fr.offlinetune.core.search.providers

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.search.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TidalProviderTest {
    private val search = """{"data":[{"id":"77","type":"tracks"}],"included":[{"id":"77","type":"tracks","attributes":{
        "title":"One More Time","isrc":"GBDUW0000053","duration":"PT5M20S","mediaTags":["LOSSLESS","HIRES_LOSSLESS"],
        "externalLinks":[{"href":"https://tidal.com/browse/track/77","meta":{"type":"TIDAL_SHARING"}}]}}]}"""
    private val tracks = """{"data":[{"id":"77","type":"tracks","relationships":{"artists":{"data":[{"id":"9","type":"artists"}]},
        "albums":{"data":[{"id":"5","type":"albums"}]}}}],"included":[{"id":"9","type":"artists","attributes":{"name":"Daft Punk"}},
        {"id":"5","type":"albums","attributes":{"title":"Discovery","imageLinks":[{"href":"small","meta":{"width":80}},{"href":"big","meta":{"width":1280}}]}}]}"""

    private fun server(tokenCode: Int = 200) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/v1/oauth2/token") -> if (tokenCode == 200) MockResponse().setBody("""{"access_token":"TT","expires_in":86400}""")
                        else MockResponse().setResponseCode(tokenCode)
                    p.startsWith("/v2/searchResults/") -> MockResponse().setBody(search)
                    p.startsWith("/v2/tracks") -> MockResponse().setBody(tracks)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun provider(s: MockWebServer, keys: Pair<String, String>? = "id" to "sec") =
        TidalProvider({ keys }, auth = s.url("").toString().trimEnd('/'), api = s.url("").toString().trimEnd('/'))

    @Test fun `search maps tracks with artists, album, cover and hi-res quality`() = runBlocking {
        server().use { s ->
            val h = provider(s).search("one more time", 5).single()
            assertEquals(Provider.TIDAL, h.provider)
            assertEquals("One More Time", h.title); assertEquals("Daft Punk", h.artist); assertEquals("Discovery", h.album)
            assertEquals(320, h.durationSec); assertEquals("GBDUW0000053", h.isrc); assertEquals("big", h.coverUrl)
            assertEquals("https://tidal.com/browse/track/77", h.url)
            assertEquals("FLAC jusqu'à 24 bit / 192 kHz", h.quality)
            val paths = (1..s.requestCount).map { s.takeRequest().path!! }
            assertTrue(paths.any { it.startsWith("/v2/searchResults/one%20more%20time/relationships/tracks") && "countryCode=FR" in it }, paths.toString())
        }
    }

    @Test fun `iso durations and quality tiers`() {
        assertEquals(320, TidalProvider.isoDuration("PT5M20S"))
        assertEquals(3725, TidalProvider.isoDuration("PT1H2M5S"))
        assertEquals(59, TidalProvider.isoDuration("PT58.6S"))
        assertEquals("FLAC 16 bit / 44,1 kHz", TidalProvider.quality(listOf("LOSSLESS")))
        assertEquals("AAC 320 kb/s", TidalProvider.quality(emptyList()))
    }

    @Test fun `refused key and missing key`() {
        server(tokenCode = 401).use { s -> assertThrows<BadKey> { runBlocking { provider(s).search("x") } } }
        server().use { s -> assertThrows<NeedsKey> { runBlocking { provider(s, null).search("x") } } }
    }

    @Tag("network")
    @Test fun `real search with env keys`() = runBlocking {
        val id = System.getenv("TIDAL_ID"); val secret = System.getenv("TIDAL_SECRET")
        assumeTrue(id != null && secret != null)
        assertTrue(TidalProvider({ id!! to secret!! }).search("daft punk one more time").isNotEmpty())
    }
}
