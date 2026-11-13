package fr.offlinetune.core.search.providers

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.search.Provider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpotifyProviderTest {
    private val searchBody = """{"tracks":{"items":[{"name":"One More Time","duration_ms":320357,
        "artists":[{"name":"Daft Punk"}],"album":{"name":"Discovery","images":[{"url":"small","width":64},{"url":"big","width":640}]},
        "external_ids":{"isrc":"GBDUW0000053"},"external_urls":{"spotify":"https://open.spotify.com/track/abc"}}]}}"""

    private fun provider(s: MockWebServer, keys: Pair<String, String>? = "id" to "secret") =
        SpotifyProvider({ keys }, accounts = s.url("").toString().trimEnd('/'), api = s.url("").toString().trimEnd('/'))

    @Test fun `token then search, token reused`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"access_token":"T1","token_type":"Bearer","expires_in":3600}"""))
            s.enqueue(MockResponse().setBody(searchBody))
            s.enqueue(MockResponse().setBody(searchBody))
            s.enqueue(MockResponse().setBody(searchBody))
            val p = provider(s)
            val h = p.search("daft punk", 5).single()
            p.search("daft punk", 15) // Spotify 2026: max 10 per request
            assertEquals(Provider.SPOTIFY, h.provider)
            assertEquals("One More Time", h.title); assertEquals("Daft Punk", h.artist); assertEquals("Discovery", h.album)
            assertEquals(320, h.durationSec); assertEquals("GBDUW0000053", h.isrc); assertEquals("big", h.coverUrl)
            assertEquals("https://open.spotify.com/track/abc", h.url)
            p.search("daft punk", 5)
            assertEquals(4, s.requestCount)
            val tokenReq = s.takeRequest()
            assertEquals("/api/token", tokenReq.path)
            assertTrue(tokenReq.getHeader("Authorization")!!.startsWith("Basic "))
            assertTrue(tokenReq.body.readUtf8().contains("grant_type=client_credentials"))
            val searchReq = s.takeRequest()
            assertEquals("Bearer T1", searchReq.getHeader("Authorization"))
            assertTrue(searchReq.path!!.startsWith("/v1/search?") && "type=track" in searchReq.path!! && "market=FR" in searchReq.path!!)
            assertTrue("limit=5" in searchReq.path!!, searchReq.path)
            val second = s.takeRequest().path!!
            assertTrue("limit=10" in second, second)
        }
    }

    @Test fun `refused key is BadKey`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_client"}"""))
            assertThrows<BadKey> { runBlocking { provider(s).search("x") } }
        }
    }

    @Test fun `no key is NeedsKey`() {
        MockWebServer().use { s -> assertThrows<NeedsKey> { runBlocking { provider(s, keys = null).search("x") } } }
    }

    @Tag("network")
    @Test fun `real search with env keys`() = runBlocking {
        val id = System.getenv("SPOTIFY_ID"); val secret = System.getenv("SPOTIFY_SECRET")
        assumeTrue(id != null && secret != null)
        assertEquals("Daft Punk", SpotifyProvider({ id!! to secret!! }).search("daft punk one more time").first().artist)
    }
}
