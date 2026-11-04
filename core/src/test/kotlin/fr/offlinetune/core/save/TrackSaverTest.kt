package fr.offlinetune.core.save

import fr.offlinetune.core.meta.ResolvedStream
import fr.offlinetune.core.model.AudioFormat
import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.ogg.OggReader
import fr.offlinetune.core.ogg.TestWebm
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TrackSaverTest {
    @TempDir lateinit var root: File

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val info = TrackInfo("One More Time", "Daft Punk", album = "Discovery", year = 2001, durationSec = 2)
    private val sheet = Sheet(
        info = info, origin = Platform.DEEZER,
        source = SourceCandidate("https://www.youtube.com/watch?v=x", "One More Time", "Daft Punk", 2, 1.0),
        candidates = emptyList(), format = AudioFormat(Container.OGG_OPUS, "Opus", 160), warnings = emptyList(),
        lyrics = LyricsResult("[00:01.00]One more time", null), coverBytes = jpeg,
    )
    private val webm = TestWebm.build(TestWebm.opusHead(), TestWebm.packets(100))

    private fun saver(fail: Boolean = false) = TrackSaver(
        resolve = { ResolvedStream(it, AudioFormat(Container.OGG_OPUS, "Opus", 160), 2, false, null) },
        download = { _, dest, progress ->
            if (fail) { dest.writeBytes(ByteArray(10)); throw NetworkLost() }
            dest.writeBytes(webm); progress(1f)
        },
    )

    @Test fun `sanitize replaces forbidden characters`() {
        assertEquals("AC_DC_ What_", TrackSaver.sanitize("AC/DC: What?"))
        assertEquals("a_b_c_d_e", TrackSaver.sanitize("a\"b<c>d|e"))
        assertEquals("Titre", TrackSaver.sanitize("  Titre... "))
        assertEquals("Sans titre", TrackSaver.sanitize(" ... "))
    }

    @Test fun `long names are capped at 120 chars`() {
        val name = TrackSaver.folderName(info.copy(title = "x".repeat(200)))
        assertTrue(name.length <= 120, "${name.length}")
        assertTrue(name.startsWith("Daft Punk - xxx"))
    }

    @Test fun `non latin names fit the 255 byte file name limit`() {
        val name = TrackSaver.folderName(info.copy(artist = "宇多田ヒカル", title = "あ".repeat(100)))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= TrackSaver.MAX_NAME_BYTES, "${name.toByteArray().size}")
        assertTrue(name.startsWith("宇多田ヒカル - あ"))
        // room left for ".", ".part", " (99)" and ".opus"
        assertTrue(".$name.part".toByteArray().size <= 255 && "$name (99).opus".toByteArray().size <= 255)
    }

    @Test fun `long non latin title saves for real`() = runBlocking {
        val edited = info.copy(artist = "宇多田ヒカル", title = "あ".repeat(100))
        val audio = saver().save(sheet, edited, true, root) {}
        assertTrue(audio.exists())
    }

    @Test fun `full save writes audio with tags, lrc and cover`() = runBlocking {
        val audio = saver().save(sheet, info, includeLyrics = true, root = root) {}
        val dir = File(root, "Daft Punk - One More Time")
        assertEquals(File(dir, "Daft Punk - One More Time.opus"), audio)
        val ogg = OggReader.inspect(audio)
        assertTrue(ogg.crcOk)
        assertEquals(listOf("One More Time"), ogg.comments["TITLE"])
        assertEquals(listOf("2001"), ogg.comments["DATE"])
        assertEquals(listOf("[00:01.00]One more time"), ogg.comments["LYRICS"])
        assertArrayEquals(jpeg, ogg.pictureBytes)
        assertTrue(File(dir, "Daft Punk - One More Time.lrc").readText().startsWith("[ti:One More Time]\n[ar:Daft Punk]\n[al:Discovery]"))
        assertArrayEquals(jpeg, File(dir, "cover.jpg").readBytes())
        assertEquals(setOf("Daft Punk - One More Time.opus", "Daft Punk - One More Time.lrc", "cover.jpg"), dir.list()!!.toSet())
    }

    @Test fun `edited info is what gets written`() = runBlocking {
        val edited = info.copy(title = "Titre corrigé", artist = "Artiste")
        val audio = saver().save(sheet, edited, includeLyrics = false, root = root) {}
        assertEquals("Artiste - Titre corrigé", audio.parentFile.name)
        assertEquals(listOf("Titre corrigé"), OggReader.inspect(audio).comments["TITLE"])
        assertEquals(null, OggReader.inspect(audio).comments["LYRICS"])
        assertFalse(File(audio.parentFile, "Artiste - Titre corrigé.lrc").exists())
    }

    @Test fun `second save gets a numbered folder`() = runBlocking {
        saver().save(sheet, info, true, root) {}
        val second = saver().save(sheet, info, true, root) {}
        assertEquals("Daft Punk - One More Time (2)", second.parentFile.name)
        assertEquals("Daft Punk - One More Time.opus", second.name)
    }

    @Test fun `missing cover and lyrics give only the audio file`() = runBlocking {
        val audio = saver().save(sheet.copy(coverBytes = null, lyrics = null), info.copy(album = null, durationSec = null), true, root) {}
        assertEquals(listOf(audio.name), audio.parentFile.list()!!.toList())
    }

    @Test fun `failed download leaves nothing behind`() {
        assertThrows<NetworkLost> { runBlocking { saver(fail = true).save(sheet, info, true, root) {} } }
        assertEquals(emptyList<String>(), root.list()!!.toList())
    }

    @Test fun `no source chosen is refused`() {
        assertThrows<fr.offlinetune.core.model.Unsupported> { runBlocking { saver().save(sheet.copy(source = null), info, true, root) {} } }
    }
}
