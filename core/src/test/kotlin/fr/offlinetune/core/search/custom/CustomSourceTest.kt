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

    @Test fun `a manifest without search placeholder or with http links is refused with a clear message`() {
        val noQ = assertThrows<InvalidManifest> { SourceManifest.parse(audius.replace("{q}", "x")) }
        assertTrue("{q}" in noQ.message!!, noQ.message)
        val http = assertThrows<InvalidManifest> { SourceManifest.parse(audius.replace("https://api.audius.co/v1/tracks/search", "http://x")) }
        assertTrue("https" in http.message!!, http.message)
        val fmt = assertThrows<InvalidManifest> { SourceManifest.parse(audius.replace("\"mp3\"", "\"flac\"")) }
        assertTrue("format" in fmt.message!!, fmt.message)
        assertThrows<InvalidManifest> { SourceManifest.parse("pas du json") }
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
