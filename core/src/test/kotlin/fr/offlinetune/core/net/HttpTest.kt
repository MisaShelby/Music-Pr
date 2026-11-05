package fr.offlinetune.core.net

import fr.offlinetune.core.model.NotFound
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.schabi.newpipe.extractor.ServiceList

class HttpTest {
    @Test
    fun `resolveRedirect follows redirects to final url`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", s.url("/b").toString()))
            s.enqueue(MockResponse().setResponseCode(301).setHeader("Location", s.url("/final?x=1").toString()))
            s.enqueue(MockResponse().setBody("ok"))
            assertEquals(s.url("/final?x=1").toString(), Http.resolveRedirect(s.url("/a").toString()))
        }
    }

    @Test
    fun `404 maps to NotFound`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(404))
            assertThrows<NotFound> { runBlocking { Http.getText(s.url("/x").toString()) } }
        }
    }

    @Test
    fun `server refusal carries its status and message`() {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"status":403,"message":"Active premium subscription required for the owner of the app."}}"""))
            val e = assertThrows<fr.offlinetune.core.model.HttpError> { runBlocking { Http.getText(s.url("/x").toString()) } }
            assertEquals(403, e.code)
            assertTrue(e.userMessage().contains("Active premium subscription required"), e.userMessage())
        }
    }

    @Test
    fun `network loss message does not talk about downloads`() {
        assertTrue("téléchargement" !in fr.offlinetune.core.model.NetworkLost().userMessage())
    }

    @Test
    fun `getText returns body`() = runBlocking {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setBody("héllo"))
            assertEquals("héllo", Http.getText(s.url("/").toString()))
        }
    }

    @Tag("network")
    @Test
    fun `newpipe extracts a youtube video title`() {
        NewPipeInit.ensure()
        val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        ex.fetchPage()
        assertTrue(ex.name.contains("Never Gonna Give You Up"), ex.name)
    }
}
