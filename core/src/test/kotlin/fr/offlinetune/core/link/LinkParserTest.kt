package fr.offlinetune.core.link

import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.Unsupported
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LinkParserTest {
    private fun link(text: String) = LinkParser.parse(text) as ParsedInput.Link

    @Test fun `spotify track with query string`() {
        val l = link("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=abc123")
        assertEquals(Platform.SPOTIFY, l.platform); assertEquals("4uLU6hMCjMI75M1A2tKUQC", l.id)
    }

    @Test fun `spotify intl track`() {
        val l = link("https://open.spotify.com/intl-fr/track/4uLU6hMCjMI75M1A2tKUQC")
        assertEquals(Platform.SPOTIFY, l.platform); assertEquals("4uLU6hMCjMI75M1A2tKUQC", l.id)
    }

    @Test fun `deezer track with locale`() {
        val l = link("https://www.deezer.com/fr/track/3135556")
        assertEquals(Platform.DEEZER, l.platform); assertEquals("3135556", l.id)
    }

    @Test fun `youtube variants`() {
        listOf(
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123",
            "https://m.youtube.com/shorts/dQw4w9WgXcQ",
        ).forEach {
            val l = link(it)
            assertEquals(Platform.YOUTUBE, l.platform, it); assertEquals("dQw4w9WgXcQ", l.id, it)
        }
    }

    @Test fun `youtube music`() {
        val l = link("https://music.youtube.com/watch?v=dQw4w9WgXcQ&feature=share")
        assertEquals(Platform.YOUTUBE_MUSIC, l.platform); assertEquals("dQw4w9WgXcQ", l.id)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", l.url)
    }

    @Test fun `soundcloud keeps url`() {
        val l = link("https://soundcloud.com/forss/flickermood?utm_source=x")
        assertEquals(Platform.SOUNDCLOUD, l.platform)
        assertEquals("https://soundcloud.com/forss/flickermood", l.url)
    }

    @Test fun `url extracted from shared text`() {
        val l = link("Écoute ça sur Spotify : https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=x merci")
        assertEquals(Platform.SPOTIFY, l.platform); assertEquals("4uLU6hMCjMI75M1A2tKUQC", l.id)
    }

    @Test fun `plain text is a query`() {
        assertEquals(ParsedInput.Query("daft punk one more time"), LinkParser.parse("  daft punk one more time "))
    }

    @Test fun `albums and playlists are unsupported`() {
        listOf(
            "https://open.spotify.com/album/abc", "https://open.spotify.com/playlist/abc",
            "https://www.deezer.com/fr/album/302127", "https://www.youtube.com/playlist?list=PL123",
            "https://soundcloud.com/forss/sets/soulhack",
        ).forEach { assertThrows<Unsupported>(it) { LinkParser.parse(it) } }
    }

    @Test fun `short links are recognised as needing resolution`() {
        listOf("https://spotify.link/abcDEF", "https://deezer.page.link/xyz", "https://link.deezer.com/s/30abc", "https://on.soundcloud.com/AbC")
            .forEach { assertEquals(true, LinkParser.isShortLink(it), it) }
    }
}
