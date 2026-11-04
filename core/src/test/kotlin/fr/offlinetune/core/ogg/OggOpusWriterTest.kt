package fr.offlinetune.core.ogg

import fr.offlinetune.core.download.StreamDownloader
import fr.offlinetune.core.meta.NewPipeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class OggOpusWriterTest {
    @TempDir lateinit var tmp: File

    private val tags = VorbisComment(
        fields = listOf("TITLE" to "Été indien", "ARTIST" to "Joe Dassin", "LYRICS" to "[00:01.00]Tu sais"),
        picture = null,
    )

    @Test fun `webm reader extracts head and packets`() {
        val head = TestWebm.opusHead()
        val packets = TestWebm.packets(5)
        for (unknown in listOf(true, false)) {
            val w = WebmReader.read(TestWebm.build(head, packets, unknown).inputStream())
            assertArrayEquals(head, w.opusHead)
            val got = w.packets.toList()
            assertEquals(5, got.size)
            got.zip(packets).forEach { (a, b) -> assertArrayEquals(b, a) }
        }
    }

    @Test fun `synthetic stream gives valid ogg with duration and tags`() {
        val f = File(tmp, "a.opus")
        f.outputStream().use { OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(100).asSequence(), tags, it) }
        val info = OggReader.inspect(f)
        assertTrue(info.crcOk)
        assertEquals(312, info.preSkip)
        assertEquals((96000 - 312) / 48000.0, info.durationSec, 1e-6)
        assertEquals(listOf("Été indien"), info.comments["TITLE"])
        assertEquals(listOf("[00:01.00]Tu sais"), info.comments["LYRICS"])
        assertEquals(false, info.hasPicture)
    }

    @Test fun `large picture spans pages and stays valid`() {
        val f = File(tmp, "b.opus")
        val pic = Random(1).nextBytes(300 * 1024)
        f.outputStream().use { OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(50).asSequence(), tags.copy(picture = pic), it) }
        val info = OggReader.inspect(f)
        assertTrue(info.crcOk)
        assertTrue(info.hasPicture)
        assertArrayEquals(pic, info.pictureBytes)
        assertTrue(info.pages >= 9, "${info.pages}")
    }

    @Test fun `samples per packet follow the TOC`() {
        assertEquals(960, OggOpusWriter.samplesPerPacket(byteArrayOf(0xFC.toByte())))         // CELT 20 ms, 1 frame
        assertEquals(1920, OggOpusWriter.samplesPerPacket(byteArrayOf(0xFD.toByte())))        // 2 frames
        assertEquals(2880, OggOpusWriter.samplesPerPacket(byteArrayOf(0x18.toByte())))        // SILK 60 ms
        assertEquals(480 * 3, OggOpusWriter.samplesPerPacket(byteArrayOf(0xF3.toByte(), 3)))  // CELT 10 ms (cfg 30), code 3 × 3
    }

    @Tag("network")
    @Test fun `real youtube opus remux`() = runBlocking {
        val stream = NewPipeSource().bestStream("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val webm = File(tmp, "in.webm")
        StreamDownloader().download(stream, webm) {}
        val out = File(tmp, "out.opus")
        val w = webm.inputStream().buffered().use { WebmReader.read(it) }
        out.outputStream().buffered().use { OggOpusWriter.write(w.opusHead, w.packets, tags, it) }
        val info = OggReader.inspect(out)
        assertTrue(info.crcOk)
        assertEquals(212.0, info.durationSec, 1.5)
        val ffprobe = runCatching { ProcessBuilder("ffprobe", "-v", "error", out.path).start().waitFor() }.getOrNull()
        if (ffprobe != null) assertEquals(0, ffprobe)
    }
}
