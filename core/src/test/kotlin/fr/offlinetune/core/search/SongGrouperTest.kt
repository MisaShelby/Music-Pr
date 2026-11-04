package fr.offlinetune.core.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SongGrouperTest {
    private fun hit(p: Provider, title: String, dur: Int?, isrc: String? = null, album: String? = null, artist: String = "Daft Punk") =
        ProviderHit(p, "u:$p:$title:$dur", title, artist, album, dur, isrc, null, "")

    @Test fun `same isrc merges despite title noise`() {
        val songs = SongGrouper.group(listOf(
            hit(Provider.DEEZER, "One More Time", 320, "FR1"),
            hit(Provider.SPOTIFY, "One More Time - Radio Edit", 321, "FR1"),
        ))
        assertEquals(1, songs.size)
        assertEquals(setOf(Provider.DEEZER, Provider.SPOTIFY), songs[0].hits.keys)
    }

    @Test fun `live version with another isrc stays separate`() {
        val songs = SongGrouper.group(listOf(
            hit(Provider.DEEZER, "One More Time", 320, "FR1"),
            hit(Provider.APPLE_MUSIC, "One More Time (Live)", 402, "FR2"),
        ))
        assertEquals(2, songs.size)
    }

    @Test fun `without isrc same title artist and close duration merge`() {
        assertEquals(1, SongGrouper.group(listOf(hit(Provider.YOUTUBE_MUSIC, "One More Time", 320), hit(Provider.APPLE_MUSIC, "One More Time", 322))).size)
        assertEquals(2, SongGrouper.group(listOf(hit(Provider.YOUTUBE_MUSIC, "One More Time", 320), hit(Provider.APPLE_MUSIC, "One More Time", 330))).size)
    }

    @Test fun `catalog hit provides the song fields`() {
        val s = SongGrouper.group(listOf(
            hit(Provider.YOUTUBE_MUSIC, "One More Time", 320, album = null),
            hit(Provider.DEEZER, "One More Time", 320, album = "Discovery"),
        )).single()
        assertEquals("Discovery", s.album)
    }

    @Test fun `first appearance order is kept`() {
        val songs = SongGrouper.group(listOf(hit(Provider.DEEZER, "B", 100), hit(Provider.DEEZER, "A", 200), hit(Provider.APPLE_MUSIC, "B", 100)))
        assertEquals(listOf("B", "A"), songs.map { it.title })
    }
}
