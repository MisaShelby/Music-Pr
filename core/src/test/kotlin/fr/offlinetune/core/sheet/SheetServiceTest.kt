package fr.offlinetune.core.sheet

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.Platform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("network")
class SheetServiceTest {
    private val svc = SheetService.create()

    @Test fun `spotify link gives a sheet with a youtube source`() = runBlocking {
        val r = svc.open("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=x") as InputResult.Ready
        val s = r.sheet
        assertEquals(Platform.SPOTIFY, s.origin)
        assertEquals("Rick Astley", s.info.artist)
        assertNotNull(s.source, "warnings=${s.warnings} candidates=${s.candidates}")
        assertTrue(s.source!!.url.contains("youtube.com/watch?v="))
        assertEquals(Container.OGG_OPUS, s.format!!.container)
        assertTrue(s.candidates.size in 1..5)
        assertNotNull(s.coverBytes)
        assertNotNull(s.lyrics)
    }

    @Test fun `deezer link`() = runBlocking {
        val s = (svc.open("https://www.deezer.com/track/3135556") as InputResult.Ready).sheet
        assertEquals(2001, s.info.year)
        assertNotNull(s.source, "warnings=${s.warnings} candidates=${s.candidates}")
    }

    @Test fun `youtube link is split into artist and title`() = runBlocking {
        val s = (svc.open("https://www.youtube.com/watch?v=A2VpR8HahKc") as InputResult.Ready).sheet // Daft Punk - One More Time (Official Audio)
        assertEquals(Platform.YOUTUBE, s.origin)
        assertEquals("Daft Punk", s.info.artist)
        assertEquals("One More Time", s.info.title)
        assertEquals("https://www.youtube.com/watch?v=A2VpR8HahKc", s.source!!.url)
    }

    @Test fun `soundcloud link`() = runBlocking {
        val s = (svc.open("https://soundcloud.com/forss/flickermood") as InputResult.Ready).sheet
        assertEquals(Platform.SOUNDCLOUD, s.origin)
        assertEquals(Container.M4A, s.format!!.container)
    }

    @Test fun `text search returns both tabs`() = runBlocking {
        val r = svc.open("daft punk around the world") as InputResult.Results
        assertTrue(r.deezer.isNotEmpty())
        assertTrue(r.youtube.isNotEmpty())
    }

    @Test fun `user can replace the source`() = runBlocking {
        val s = (svc.open("https://www.deezer.com/track/3135556") as InputResult.Ready).sheet
        val s2 = svc.withSource(s, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", s2.source!!.url)
        assertEquals(s.info, s2.info)
    }
}
