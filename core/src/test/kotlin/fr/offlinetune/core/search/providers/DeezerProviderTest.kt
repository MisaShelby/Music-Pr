package fr.offlinetune.core.search.providers

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeezerProviderTest {
    @Test fun `quota error is a failure, not an empty result`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("""{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""))
            assertThrows<Exception> { runBlocking { DeezerProvider(s.url("").toString().trimEnd('/')).search("x") } }
        }
    }
}
