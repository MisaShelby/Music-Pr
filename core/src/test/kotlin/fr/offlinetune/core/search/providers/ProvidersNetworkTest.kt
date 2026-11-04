package fr.offlinetune.core.search.providers

import fr.offlinetune.core.search.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("network")
class ProvidersNetworkTest {
    private val q = "daft punk one more time"

    @Test fun deezer() = runBlocking {
        val h = DeezerProvider().search(q).first()
        assertEquals(Provider.DEEZER, h.provider)
        assertEquals("Daft Punk", h.artist)
        assertTrue(h.album != null && h.coverUrl != null && h.url.startsWith("https://www.deezer.com/track/"))
    }

    @Test fun youtubeMusic() = runBlocking {
        val hits = YouTubeMusicProvider().search(q)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.provider == Provider.YOUTUBE_MUSIC && "watch?v=" in it.url })
    }

    @Test fun soundcloud() = runBlocking {
        val hits = SoundCloudProvider().search(q)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.provider == Provider.SOUNDCLOUD && it.url.startsWith("https://soundcloud.com/") }, hits.toString())
    }
}
