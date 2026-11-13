package fr.offlinetune.core.lyrics

import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.TrackInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class LyricsTest {
    private val info = TrackInfo("One More Time", "Daft Punk", album = "Discovery", durationSec = 245)

    @Tag("network")
    @Test fun `lrclib finds synced lyrics`() = runBlocking {
        val r = LrclibClient().find(TrackInfo("One More Time", "Daft Punk", album = "Discovery", durationSec = 320))
        assertNotNull(r?.synced, "$r")
        assertTrue(r!!.synced!!.lines().first().startsWith("["))
    }

    @Tag("network")
    @Test fun `lrclib returns null for nonsense`() = runBlocking {
        assertEquals(null, LrclibClient().find(TrackInfo("zzqx nonexistent qwv", "nobody qq", durationSec = 100)))
    }

    @Test fun `build writes headers then synced body`() {
        val lrc = LrcFormat.build(info, LyricsResult("[00:01.00]One more time\n[00:03.50]We're gonna celebrate", "x"))
        assertEquals(
            "[ti:One More Time]\n[ar:Daft Punk]\n[al:Discovery]\n[length:04:05]\n[00:01.00]One more time\n[00:03.50]We're gonna celebrate\n",
            lrc,
        )
    }

    @Test fun `build omits missing album and length, uses plain text`() {
        val lrc = LrcFormat.build(TrackInfo("T", "A"), LyricsResult(null, "line one\nline two"))
        assertFalse(lrc.contains("[al:"))
        assertFalse(lrc.contains("[length:"))
        assertEquals("[ti:T]\n[ar:A]\nline one\nline two\n", lrc)
    }

    @Test fun `parse handles multiple stamps and ms precision`() {
        val p = LrcFormat.parse("[ti:x]\n[00:12.50][01:00.00]x\n[00:13.123]y")
        assertEquals(listOf(12500L to "x", 13123L to "y", 60000L to "x"), p)
    }

    @Test fun `parse of plain text is empty`() {
        assertEquals(emptyList<Pair<Long, String>>(), LrcFormat.parse("just text\nmore"))
    }

    @Test fun `preview strips timestamps`() {
        assertEquals(listOf("a", "b"), LrcFormat.preview(LyricsResult("[00:01.00]a\n[00:02.00]\n[00:03.00]b", null), 2))
        assertEquals(listOf("p1", "p2"), LrcFormat.preview(LyricsResult(null, "p1\n\np2\np3"), 2))
    }
}
