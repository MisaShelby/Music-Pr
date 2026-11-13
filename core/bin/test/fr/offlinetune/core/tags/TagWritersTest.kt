package fr.offlinetune.core.tags

import fr.offlinetune.core.model.TrackInfo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer

class TagWritersTest {
    @TempDir lateinit var tmp: File
    private val info = TrackInfo("Été indien", "Joe Dassin", album = "Joe Dassin (Le Costume Blanc)", albumArtist = "Joe Dassin",
        year = 1975, trackNumber = 3, genre = "Chanson")
    private val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    // ---------- ID3 ----------

    private val mp3Body = ByteArray(4000) { i -> intArrayOf(0xFF, 0xFB, 0x90, 0x64)[i % 4].toByte() }

    private fun oldTagged(): File {
        val oldTag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 10) + ByteArray(10)
        val v1 = "TAG".toByteArray() + ByteArray(125)
        return File(tmp, "in.mp3").apply { writeBytes(oldTag + mp3Body + v1) }
    }

    @Test fun `id3 rewrite keeps audio bytes and writes v2_3 frames`() {
        val out = File(tmp, "out.mp3")
        Id3Writer.rewrite(oldTagged(), out, info, cover, "Tu sais")
        val b = out.readBytes()
        assertEquals("ID3", String(b, 0, 3)); assertEquals(3, b[3].toInt()); assertEquals(0, b[4].toInt())
        val size = ((b[6].toInt() and 0x7F) shl 21) or ((b[7].toInt() and 0x7F) shl 14) or ((b[8].toInt() and 0x7F) shl 7) or (b[9].toInt() and 0x7F)
        val frames = Id3Writer.parseFrames(b.copyOfRange(10, 10 + size))
        assertEquals("Été indien", frames["TIT2"])
        assertEquals("Joe Dassin", frames["TPE1"])
        assertEquals("1975", frames["TYER"])
        assertEquals("3", frames["TRCK"])
        assertTrue(frames.containsKey("APIC"))
        assertTrue(frames.containsKey("USLT"))
        assertArrayEquals(mp3Body, b.copyOfRange(10 + size, b.size))
    }

    // ---------- MP4 ----------

    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    /** ftyp + moov(trak/mdia/minf/stbl/stco → offset of the audio bytes in mdat) + mdat. */
    private fun syntheticMp4(audio: ByteArray): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(4) + "isomM4A ".toByteArray())
        fun moov(offset: Int): ByteArray {
            val stco = box("stco", ByteBuffer.allocate(12).putInt(0).putInt(1).putInt(offset).array())
            return box("moov", box("mvhd", ByteArray(100)) + box("trak", box("mdia", box("minf", box("stbl", stco)))))
        }
        val provisional = moov(0)
        val offset = ftyp.size + provisional.size + 8
        return ftyp + moov(offset) + box("mdat", audio)
    }

    @Test fun `mp4 tags are added and chunk offsets still point at the audio`() {
        val audio = ByteArray(500) { (it * 7).toByte() }
        val input = File(tmp, "in.m4a").apply { writeBytes(syntheticMp4(audio)) }
        val out = File(tmp, "out.m4a")
        Mp4Tags.write(input, out, info, cover, "Tu sais")
        val read = Mp4Tags.read(out)
        assertEquals("Été indien", read["©nam"])
        assertEquals("Joe Dassin", read["©ART"])
        assertEquals("1975", read["©day"])
        assertEquals("Tu sais", read["©lyr"])
        assertEquals("3", read["trkn"])
        assertTrue(read.containsKey("covr"))
        val offset = Mp4Tags.firstChunkOffset(out)
        assertArrayEquals(audio, out.readBytes().copyOfRange(offset.toInt(), offset.toInt() + audio.size))
    }

    @Test fun `mp4 rewrite replaces an existing udta instead of duplicating`() {
        val input = File(tmp, "in.m4a").apply { writeBytes(syntheticMp4(ByteArray(10))) }
        val once = File(tmp, "once.m4a"); val twice = File(tmp, "twice.m4a")
        Mp4Tags.write(input, once, info, null, null)
        Mp4Tags.write(once, twice, info.copy(title = "Autre"), null, null)
        assertEquals("Autre", Mp4Tags.read(twice)["©nam"])
        assertEquals(1, Regex("udta").findAll(String(twice.readBytes(), Charsets.ISO_8859_1)).count())
    }
}
