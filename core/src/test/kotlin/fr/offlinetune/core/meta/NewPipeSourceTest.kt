package fr.offlinetune.core.meta

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.Platform
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("network")
class NewPipeSourceTest {
    private val np = NewPipeSource()
    private val rick = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun `youtube best stream is opus`() = runBlocking {
        val s = np.bestStream(rick)
        assertEquals(Container.OGG_OPUS, s.format.container)
        assertTrue(s.format.bitrateKbps >= 120, "${s.format}")
        assertTrue(s.durationSec!! in 210..215)
    }

    @Test fun `youtube info`() = runBlocking {
        val (info, platform) = np.info(rick)
        assertEquals(Platform.YOUTUBE, platform)
        assertEquals("Rick Astley", info.artist)
        assertTrue(info.title.contains("Never Gonna Give You Up"))
        assertTrue(info.coverUrl!!.startsWith("http"))
    }

    @Test fun `youtube music song search`() = runBlocking {
        val c = np.searchMusicSongs("Daft Punk One More Time")
        assertTrue(c.isNotEmpty())
        assertTrue(c.all { it.url.contains("watch?v=") }, c.toString())
        assertTrue(c.any { it.artist.contains("Daft Punk") }, c.toString())
    }

    @Test fun `youtube general search`() = runBlocking {
        val hits = np.searchYoutube("daft punk around the world live")
        assertTrue(hits.size >= 3)
    }

    @Test fun `soundcloud best stream`() = runBlocking {
        val s = np.bestStream("https://soundcloud.com/forss/flickermood")
        assertTrue(s.format.container == Container.MP3 || s.format.container == Container.M4A, "${s.format}")
        val (info, platform) = np.info("https://soundcloud.com/forss/flickermood")
        assertEquals(Platform.SOUNDCLOUD, platform)
        assertTrue(info.title.isNotBlank())
    }
}
