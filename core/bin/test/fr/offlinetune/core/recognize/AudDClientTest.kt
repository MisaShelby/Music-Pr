package fr.offlinetune.core.recognize

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AudDClientTest {
    @TempDir lateinit var tmp: File
    private val clip get() = File(tmp, "clip.m4a").apply { writeBytes(ByteArray(2000) { it.toByte() }) }

    @Test fun `sends token, file and requested platforms, parses the match`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"status":"success","result":{"artist":"Daft Punk","title":"One More Time",
                "album":"Discovery","release_date":"2001-03-12","song_link":"https://lis.tn/x",
                "deezer":{"link":"https://www.deezer.com/track/3135553"},"spotify":{"external_urls":{"spotify":"https://open.spotify.com/track/y"}}}}"""))
            val r = AudDClient({ "TOKEN" }, s.url("/").toString()).recognize(clip)!!
            assertEquals("Daft Punk", r.artist); assertEquals("One More Time", r.title); assertEquals("Discovery", r.album)
            assertEquals("https://www.deezer.com/track/3135553", r.deezerUrl)
            assertEquals("https://open.spotify.com/track/y", r.spotifyUrl)
            val body = s.takeRequest().body.readUtf8()
            assertTrue("name=\"api_token\"" in body && "TOKEN" in body)
            assertTrue("name=\"file\"" in body)
            assertTrue("spotify,deezer,apple_music" in body)
        }
    }

    @Test fun `no match is null`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"status":"success","result":null}"""))
            assertNull(AudDClient({ "T" }, s.url("/").toString()).recognize(clip))
        }
    }

    @Test fun `token errors`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"status":"error","error":{"error_code":900,"error_message":"Wrong API token"}}"""))
            assertThrows<BadKey> { runBlocking { AudDClient({ "bad" }, s.url("/").toString()).recognize(clip) } }
        }
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"status":"error","error":{"error_code":901,"error_message":"limit reached"}}"""))
            assertThrows<NeedsKey> { runBlocking { AudDClient({ null }, s.url("/").toString()).recognize(clip) } }
        }
    }
}
