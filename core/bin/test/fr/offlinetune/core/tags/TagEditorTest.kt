package fr.offlinetune.core.tags

import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.ogg.OggOpusWriter
import fr.offlinetune.core.ogg.OggReader
import fr.offlinetune.core.ogg.TestWebm
import fr.offlinetune.core.ogg.VorbisComment
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TagEditorTest {
    @TempDir lateinit var tmp: File
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9, 9, 9)

    @Test fun `opus tags are rewritten without touching the audio packets`() {
        val f = File(tmp, "a.opus")
        f.outputStream().use { OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(60).asSequence(), VorbisComment(fields = listOf("TITLE" to "Ancien")), it) }
        val before = OggReader.packets(f)
        TagEditor.write(f, TrackInfo("Nouveau titre", "Artiste", album = "Album", year = 1999), jpeg, "[00:01.00]la")
        val info = OggReader.inspect(f)
        assertTrue(info.crcOk)
        assertEquals(listOf("Nouveau titre"), info.comments["TITLE"])
        assertEquals(listOf("1999"), info.comments["DATE"])
        assertEquals(listOf("[00:01.00]la"), info.comments["LYRICS"])
        assertArrayEquals(jpeg, info.pictureBytes)
        val after = OggReader.packets(f)
        assertArrayEquals(before.first, after.first)
        assertEquals(before.second.size, after.second.size)
        before.second.zip(after.second).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test fun `opus keeps its existing cover when none is given`() {
        val f = File(tmp, "b.opus")
        f.outputStream().use { OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(10).asSequence(), VorbisComment(fields = listOf("TITLE" to "x"), picture = jpeg), it) }
        TagEditor.write(f, TrackInfo("y", "z"), cover = null, lyrics = null)
        assertArrayEquals(jpeg, OggReader.inspect(f).pictureBytes)
    }

    @Test fun `mp3 and m4a keep their cover and lyrics when only text changes`() {
        val mp3 = File(tmp, "x.mp3").apply { writeBytes(ByteArray(400) { intArrayOf(0xFF, 0xFB, 0x90, 0x64)[it % 4].toByte() }) }
        val first = File(tmp, "y.mp3")
        Id3Writer.rewrite(mp3, first, TrackInfo("A", "B"), jpeg, "paroles")
        TagEditor.write(first, TrackInfo("Titre changé", "B"), cover = null, lyrics = null)
        val b = first.readBytes()
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val frames = Id3Writer.parseFrames(b.copyOfRange(10, 10 + size))
        assertEquals("Titre changé", frames["TIT2"])
        assertTrue("APIC" in frames && "USLT" in frames, frames.keys.toString())
    }

    private fun opusFile(name: String, title: String = "x", n: Int = 10) = File(tmp, name).apply {
        outputStream().use { OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(n).asSequence(), VorbisComment(fields = listOf("TITLE" to title)), it) }
    }

    @Test fun `vorbis ogg is refused and left untouched`() {
        val f = opusFile("v.ogg")
        val bytes = f.readBytes()
        // Turn it into a "Vorbis" stream: first packet no longer starts with OpusHead.
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("OpusHead")
        "\u0001vorbis\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(bytes, i)
        f.writeBytes(bytes)
        assertThrows<Unsupported> { TagEditor.write(f, TrackInfo("t", "a"), null, null) }
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test fun `chained or multi-stream ogg is refused`() {
        val f = File(tmp, "c.opus").apply { writeBytes(opusFile("p1.opus").readBytes() + opusFile("p2.opus").readBytes()) }
        val before = f.readBytes()
        assertThrows<Unsupported> { TagEditor.write(f, TrackInfo("t", "a"), null, null) }
        assertArrayEquals(before, f.readBytes())
    }

    @Test fun `truncated ogg is refused`() {
        val good = opusFile("t.opus", n = 200).readBytes()
        val f = File(tmp, "t2.opus").apply { writeBytes(good.copyOf(good.size - 37) + "garbage".toByteArray()) }
        assertThrows<Unsupported> { TagEditor.write(f, TrackInfo("t", "a"), null, null) }
    }

    @Test fun `other opus tags are kept, edited ones replaced`() {
        val f = File(tmp, "k.opus").apply {
            outputStream().use {
                OggOpusWriter.write(TestWebm.opusHead(), TestWebm.packets(10).asSequence(),
                    VorbisComment(fields = listOf("TITLE" to "old", "R128_TRACK_GAIN" to "-512", "DISCNUMBER" to "2", "COMPOSER" to "X")), it)
            }
        }
        TagEditor.write(f, TrackInfo("new", "A"), null, null)
        val c = OggReader.inspect(f).comments
        assertEquals(listOf("new"), c["TITLE"])
        assertEquals(listOf("-512"), c["R128_TRACK_GAIN"])
        assertEquals(listOf("2"), c["DISCNUMBER"])
        assertEquals(listOf("X"), c["COMPOSER"])
    }

    @Test fun `other mp3 frames are kept`() {
        val mp3 = File(tmp, "o.mp3").apply { writeBytes(ByteArray(400) { intArrayOf(0xFF, 0xFB, 0x90, 0x64)[it % 4].toByte() }) }
        val tagged = File(tmp, "o2.mp3")
        Id3Writer.rewrite(mp3, tagged, TrackInfo("A", "B"), null, null, extraFrames = listOf("TPOS" to "2", "TCOM" to "Composer"))
        TagEditor.write(tagged, TrackInfo("C", "B"), null, null)
        val b = tagged.readBytes()
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val frames = Id3Writer.parseFrames(b.copyOfRange(10, 10 + size))
        assertEquals("C", frames["TIT2"]); assertEquals("2", frames["TPOS"]); assertEquals("Composer", frames["TCOM"])
    }

    @Test fun `flac is not editable`() {
        val f = File(tmp, "c.flac").apply { writeBytes(ByteArray(10)) }
        assertThrows<Unsupported> { TagEditor.write(f, TrackInfo("t", "a"), null, null) }
    }
}
