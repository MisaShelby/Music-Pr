package fr.offlinetune.core.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HlsTest {
    @Test fun `parses map, segments and total duration with relative urls`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4?sig=1"
            #EXTINF:10.0,
            data000.m4s?sig=1
            #EXTINF:9.5,
            https://cdn.example/abs/data001.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = Hls.parse(text, "https://host.example/path/playlist.m3u8?x=1")
        assertEquals("https://host.example/path/init.mp4?sig=1", p.initUrl)
        assertEquals(listOf("https://host.example/path/data000.m4s?sig=1", "https://cdn.example/abs/data001.m4s"), p.segments)
        assertEquals(19.5, p.totalSec, 1e-9)
    }

    @Test fun `no map`() {
        val p = Hls.parse("#EXTM3U\n#EXTINF:30.0,\n/a.mp3\n", "https://h.example/x/y.m3u8")
        assertEquals(null, p.initUrl)
        assertEquals(listOf("https://h.example/a.mp3"), p.segments)
    }

    @Test fun `preview rule`() {
        assertEquals(true, Hls.isPreview(streamSec = 30.0, announcedSec = 213))
        assertEquals(false, Hls.isPreview(streamSec = 212.0, announcedSec = 213))
        assertEquals(false, Hls.isPreview(streamSec = 20.0, announcedSec = 30)) // short track, gap ≤ 20 s
    }
}
