package fr.offlinetune.core.meta

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeezerQuotaTest {
    @Test fun `quota error is retried instead of being treated as not found`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""))
            s.enqueue(MockResponse().setBody("""{"data":[{"id":1,"title":"T","artist":{"name":"A"},"duration":100,"album":{"cover_medium":"c"}}]}"""))
            val hits = DeezerClient(base = s.url("").toString().trimEnd('/'), quotaDelayMs = 10).search("x")
            assertEquals(1, hits.size)
            assertEquals(2, s.requestCount)
        }
    }
}

class DeezerFindBestTest {
    @org.junit.jupiter.api.Test fun `findBest prefers the artist's own album over a compilation`() = kotlinx.coroutines.runBlocking {
        MockWebServer().use { s ->
            s.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val p = request.path!!
                    val body = when {
                        p.startsWith("/search/track") -> """{"data":[
                            {"id":1,"title":"Song","artist":{"name":"Band"},"duration":200,"album":{"id":10}},
                            {"id":2,"title":"Song","artist":{"name":"Band"},"duration":200,"album":{"id":20}}]}"""
                        p == "/track/1" -> """{"id":1,"title":"Song","artist":{"name":"Band"},"duration":200,"release_date":"2015-01-01","album":{"id":10,"title":"Hits Compilation"}}"""
                        p == "/track/2" -> """{"id":2,"title":"Song","artist":{"name":"Band"},"duration":200,"release_date":"1990-01-01","album":{"id":20,"title":"First Album"}}"""
                        p == "/album/10" -> """{"artist":{"name":"Various Artists"}}"""
                        p == "/album/20" -> """{"artist":{"name":"Band"}}"""
                        else -> """{"error":{"type":"DataException","code":800}}"""
                    }
                    return MockResponse().setBody(body)
                }
            }
            val t = DeezerClient(base = s.url("").toString().trimEnd('/')).findBest("Song", "Band", 200)
            assertEquals("First Album", t!!.album)
            assertEquals("Band", t.albumArtist)
        }
    }
}
