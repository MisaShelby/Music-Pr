package fr.offlinetune.core.search

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.search.providers.DeezerProvider
import fr.offlinetune.core.sheet.SheetService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("network")
class SourceResolverTest {
    private val sheets = SheetService.create()
    private val resolver = SourceResolver.create()

    private suspend fun deezerOnly(q: String): SongResult {
        val h = DeezerProvider().search(q, 1).first()
        return SongResult(h.title, h.artist, h.album, h.durationSec, h.coverUrl, mapOf(Provider.DEEZER to h))
    }

    @Test fun `paid-only song gets a youtube match, paid platform is listen-only`() = runBlocking {
        val src = resolver.resolve(deezerOnly("daft punk harder better faster stronger"))
        val opt = src.downloadable.single()
        assertTrue(opt.isMatch)
        assertEquals(Provider.YOUTUBE_MUSIC, opt.provider)
        assertEquals(Container.OGG_OPUS, opt.format.container)
        assertEquals(listOf(Provider.DEEZER), src.listenOnly.map { it.provider })
        assertTrue(src.downloadable.none { !it.provider.downloadable })
    }

    @Test fun `youtube hit is used directly`() = runBlocking {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val h = ProviderHit(Provider.YOUTUBE_MUSIC, url, "Never Gonna Give You Up", "Rick Astley", null, 213, null, null, "")
        val src = resolver.resolve(SongResult(h.title, h.artist, null, 213, null, mapOf(Provider.YOUTUBE_MUSIC to h)))
        assertEquals(url, src.downloadable.single().url)
        assertFalse(src.downloadable.single().isMatch)
    }

    @Test fun `choosing a source builds the v1 sheet with catalog info`() = runBlocking {
        val song = deezerOnly("daft punk harder better faster stronger")
        val opt = resolver.resolve(song).downloadable.first()
        val sheet = sheets.fromSong(song, opt)
        assertEquals(opt.url, sheet.source!!.url)
        assertEquals(2001, sheet.info.year)
        assertEquals(opt.format, sheet.format)
    }
}
