package fr.offlinetune.core.search.custom

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.net.parseJson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CustomSourceTest {
    private val audius = """
        {"name":"Audius","search":"https://api.audius.co/v1/tracks/search?query={q}","results":"$.data",
         "id":"$.id","title":"$.title","artist":"$.user.name","duration":"$.duration","cover":"$.artwork.480x480",
         "audio":"https://api.audius.co/v1/tracks/{id}/stream","format":"mp3","quality":"MP3","playable":"$.is_streamable"}
    """.trimIndent()

    @Test fun `json paths read nested fields, indexes and keys with odd characters`() {
        val j = parseJson("""{"a":{"b":[{"c":"x"},{"c":"y"}],"480x480":"img"},"n":42}""")
        assertEquals("y", JsonPaths.text(j, "$.a.b[1].c"))
        assertEquals("img", JsonPaths.text(j, "$.a.480x480"))
        assertEquals("42", JsonPaths.text(j, "$.n"))
        assertNull(JsonPaths.text(j, "$.a.missing"))
        assertEquals(2, JsonPaths.list(j, "$.a.b").size)
    }

    @Test fun `a valid manifest is read`() {
        val m = SourceManifest.parse(audius)
        assertEquals("Audius", m.name)
        assertEquals(Container.MP3, m.container)
    }

    @Test fun `nothing is required but a search address - the rest has defaults`() {
        val m = SourceManifest.parse("""{"api":{"searchUrl":"https://x.org/find?term={query}"}}""", fallbackName = "mon-depot")
        assertEquals("mon-depot", m.name)
        assertEquals("https://x.org/find?term={q}", m.search)
        assertEquals(Container.MP3, m.container)
        // an address ending with "=" gets the words appended
        assertEquals("https://x.org/s?q={q}", SourceManifest.parse("""{"search":"https://x.org/s?q="}""").search)
        // unknown format: mp3 by default instead of refusing
        assertEquals(Container.MP3, SourceManifest.parse("""{"search":"https://x.org/s?q={q}","format":"flac"}""").container)
    }

    @Test fun `a file without any search address or not json says so`() {
        val e = assertThrows<InvalidManifest> { SourceManifest.parse("""{"name":"x","items":[1,2]}""") }
        assertTrue("recherche" in e.message!!, e.message)
        assertThrows<InvalidManifest> { SourceManifest.parse("pas du json") }
    }

    @Test fun `an extension catalogue is recognised and explained`() {
        val reg = """[{"id":"x","name":"x","display_name":"X","version":"1","download_url":"https://h/x.sflx","sha256":"ab","category":"music","tags":[],"min_app_version":"1"}]"""
        val e = assertThrows<InvalidManifest> { SourceManifest.parse(reg) }
        assertTrue("catalogue d'extensions" in e.message!!, e.message)
        val wrapped = assertThrows<InvalidManifest> { SourceManifest.parse("""{"extensions":$reg}""") }
        assertTrue("catalogue d'extensions" in wrapped.message!!, wrapped.message)
    }

    @Test fun `results and fields are found automatically when not described`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"meta":{"n":1},"tracks":[{"track_id":7,"name":"Auto","artist_name":"Who","image":"https://img/a","stream_url":"https://cdn/a.m4a","duration":"120"}]}"""))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val m = SourceManifest.parse("""{"search":"$base/s?q={q}"}""", allowHttp = true)
        val h = CustomSearch.search(m, null, "x").single()
        assertEquals("Auto", h.title); assertEquals("Who", h.artist); assertEquals("https://img/a", h.coverUrl); assertEquals(120, h.durationSec)
        assertEquals(Container.M4A to "https://cdn/a.m4a", CustomAudio.decode(h.url))
        server.shutdown()
    }

    @Test fun `github links give the raw manifest address`() {
        assertEquals(
            listOf("https://raw.githubusercontent.com/me/src/HEAD/musiquepr-source.json"),
            GithubLinks.manifestUrls("https://github.com/me/src"),
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/me/src/main/sources/audius/musiquepr-source.json"),
            GithubLinks.manifestUrls("https://github.com/me/src/tree/main/sources/audius/"),
        )
        assertEquals(
            listOf("https://raw.githubusercontent.com/me/src/main/a.json"),
            GithubLinks.manifestUrls("https://github.com/me/src/blob/main/a.json"),
        )
        assertEquals(listOf("https://example.org/s.json"), GithubLinks.manifestUrls("https://example.org/s.json"))
    }

    @Test fun `audio link round trips through the hit url`() {
        val u = CustomAudio.encode(Container.M4A, "https://x.org/a?b=1")
        assertTrue(CustomAudio.isCustom(u))
        assertEquals(Container.M4A to "https://x.org/a?b=1", CustomAudio.decode(u))
        assertTrue(!CustomAudio.isCustom("https://www.youtube.com/watch?v=1"))
    }

    @Test fun `search maps the results to hits with the audio link`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"T1","title":"Song","user":{"name":"Art"},"duration":201,"artwork":{"480x480":"https://img/1"}},{"title":"no id"},{"id":"T2","title":"Locked","user":{"name":"X"},"is_streamable":false}]}"""))
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val m = SourceManifest.parse(audius.replace("https://api.audius.co/v1/tracks/search", "$base/search"), allowHttp = true)
        val hits = CustomSearch.search(m, key = null, query = "a b")
        assertEquals(1, hits.size)
        val h = hits[0]
        assertEquals("Song", h.title); assertEquals("Art", h.artist); assertEquals(201, h.durationSec)
        assertEquals("https://img/1", h.coverUrl)
        assertEquals("Audius", h.label)
        assertEquals(Container.MP3 to "https://api.audius.co/v1/tracks/T1/stream", CustomAudio.decode(h.url))
        assertEquals("/search?query=a+b", server.takeRequest().path)
        server.shutdown()
    }
}
