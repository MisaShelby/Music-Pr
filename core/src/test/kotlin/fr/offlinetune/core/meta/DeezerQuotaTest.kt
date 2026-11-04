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
