package fr.offlinetune.core.search.providers

import fr.offlinetune.core.model.NeedsKey
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URLDecoder

class SpotifyAccountTest {
    @Test fun `pkce challenge matches rfc 7636 appendix b`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it in "-._~" }, v)
    }

    private var tokens: SpotifyTokens? = null

    private fun account(s: MockWebServer, now: () -> Long = { 1_000L }) = SpotifyAccount(
        clientId = { "cid" }, load = { tokens }, save = { tokens = it },
        accounts = s.url("").toString().trimEnd('/'), api = s.url("").toString().trimEnd('/'), clock = now,
    )

    @Test fun `authorize url carries client, redirect, challenge and scopes`() {
        MockWebServer().use { s ->
            val url = account(s).authorizeUrl("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "st")
            val q = URLDecoder.decode(url.substringAfter('?'), "UTF-8")
            assertTrue(url.startsWith(s.url("/authorize").toString()))
            listOf("client_id=cid", "response_type=code", "redirect_uri=fr.offlinetune://spotify-callback", "code_challenge_method=S256",
                "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", "state=st", "scope=playlist-read-private playlist-read-collaborative")
                .forEach { assertTrue(it in q, "$it in $q") }
        }
    }

    @Test fun `code exchange posts pkce fields and stores tokens`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"access_token":"A1","refresh_token":"R1","expires_in":3600}"""))
            account(s).exchange("CODE", "VER")
            assertEquals(SpotifyTokens("A1", "R1", 1_000L + 3_540_000L), tokens)
            val body = URLDecoder.decode(s.takeRequest().body.readUtf8(), "UTF-8")
            listOf("grant_type=authorization_code", "code=CODE", "code_verifier=VER", "client_id=cid", "redirect_uri=fr.offlinetune://spotify-callback")
                .forEach { assertTrue(it in body, "$it in $body") }
        }
    }

    @Test fun `playlists are paged and tracks parsed, null tracks skipped`() = runBlocking {
        tokens = SpotifyTokens("A1", "R1", 10_000_000L)
        MockWebServer().use { s ->
            val base = s.url("").toString().trimEnd('/')
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path!!.startsWith("/v1/me/playlists?") && "offset=50" !in request.path!! -> MockResponse().setBody(
                        """{"items":[{"id":"p1","name":"Soirée","images":[{"url":"img"}],"tracks":{"total":2}}],"next":"$base/v1/me/playlists?offset=50&limit=50"}""")
                    request.path!!.startsWith("/v1/me/playlists") -> MockResponse().setBody("""{"items":[{"id":"p2","name":"Calme","images":[],"items":{"total":0}}],"next":null}""")
                    request.path!!.startsWith("/v1/playlists/p1/tracks") -> MockResponse().setBody("""{"items":[
                        {"track":{"name":"One More Time","duration_ms":320000,"artists":[{"name":"Daft Punk"}],"album":{"name":"Discovery","images":[{"url":"big","width":640}]},
                         "external_ids":{"isrc":"GBDUW0000053"},"external_urls":{"spotify":"https://open.spotify.com/track/x"}}},
                        {"track":null}],"next":null}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val lists = account(s).playlists()
            assertEquals(listOf("Soirée" to 2, "Calme" to 0), lists.map { it.name to it.trackCount })
            val t = account(s).tracks("p1").single()
            assertEquals("One More Time", t.title); assertEquals("GBDUW0000053", t.isrc); assertEquals("https://open.spotify.com/track/x", t.url)
            assertEquals("Bearer A1", s.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `expired token is refreshed first`() = runBlocking {
        tokens = SpotifyTokens("OLD", "R1", 500L)
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"access_token":"NEW","expires_in":3600}"""))
            s.enqueue(MockResponse().setBody("""{"items":[],"next":null}"""))
            account(s).playlists()
            assertTrue(s.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
            assertEquals("Bearer NEW", s.takeRequest().getHeader("Authorization"))
            assertEquals("R1", tokens!!.refresh) // Spotify may omit a new refresh token: keep the old one
        }
    }

    @Test fun `not logged in asks to connect`() {
        tokens = null
        MockWebServer().use { s -> assertThrows<NeedsKey> { runBlocking { account(s).playlists() } } }
    }
}
