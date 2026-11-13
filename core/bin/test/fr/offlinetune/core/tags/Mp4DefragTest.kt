package fr.offlinetune.core.tags

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer

class Mp4DefragTest {
    @TempDir lateinit var tmp: File

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val b = body.fold(ByteArray(0)) { a, c -> a + c }
        return ByteBuffer.allocate(8 + b.size).putInt(8 + b.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(b).array()
    }
    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()

    private val samples = List(6) { i -> ByteArray(100 + i * 10) { (i * 31 + it).toByte() } }

    /** ftyp + moov(mvhd, trak(tkhd, mdia(mdhd, minf/stbl with empty tables)), mvex/trex) + 2 × (moof + mdat). */
    private fun fragmented(): ByteArray {
        val stbl = box("stbl", box("stsd", ints(0, 0)), box("stts", ints(0, 0)), box("stsc", ints(0, 0)), box("stsz", ints(0, 0, 0)), box("stco", ints(0, 0)))
        val mdhd = box("mdhd", ints(0, 0, 0, 44100, 0, 0))
        val trak = box("trak", box("tkhd", ints(7, 0, 0, 1, 0, 0) + ByteArray(60)), box("mdia", mdhd, box("minf", stbl)))
        val mvex = box("mvex", box("trex", ints(0, 1, 1, 1024, 0, 0)))
        val moov = box("moov", box("mvhd", ints(0, 0, 0, 1000, 0) + ByteArray(80)), trak, mvex)
        var out = box("ftyp", "M4A ".toByteArray() + ints(0) + "isomiso6".toByteArray()) + moov
        for (frag in 0 until 2) {
            val group = samples.subList(frag * 3, frag * 3 + 3)
            // trun: flags 0x201 (data-offset + sample-size), 3 samples
            fun moof(dataOffset: Int) = box("moof", box("mfhd", ints(0, frag + 1)),
                box("traf", box("tfhd", ints(0x020000, 1)), box("tfdt", ints(0, frag * 3 * 1024)),
                    box("trun", ints(0x000201, 3, dataOffset) + ints(*group.map { it.size }.toIntArray()))))
            val size = moof(0).size
            out += moof(size + 8) + box("mdat", *group.toTypedArray())
        }
        return out
    }

    @Test fun `fragmented file becomes a plain mp4 with sample tables and duration`() {
        val input = File(tmp, "in.m4a").apply { writeBytes(fragmented()) }
        val out = File(tmp, "out.m4a")
        assertTrue(Mp4Defrag.isFragmented(input))
        Mp4Defrag.defragment(input, out)
        assertFalse(Mp4Defrag.isFragmented(out))
        val info = Mp4Defrag.inspect(out)
        assertEquals(samples.map { it.size }, info.sampleSizes)
        assertEquals(6L * 1024, info.mediaDuration)
        assertEquals(6L * 1024 * 1000 / 44100, info.movieDuration)
        val data = out.readBytes()
        val joined = samples.fold(ByteArray(0)) { a, b -> a + b }
        assertArrayEquals(joined, data.copyOfRange(info.chunkOffset.toInt(), info.chunkOffset.toInt() + joined.size))
    }

    @Test fun `tags can be written on the result`() {
        val input = File(tmp, "in.m4a").apply { writeBytes(fragmented()) }
        val plain = File(tmp, "plain.m4a"); val tagged = File(tmp, "tagged.m4a")
        Mp4Defrag.defragment(input, plain)
        Mp4Tags.write(plain, tagged, fr.offlinetune.core.model.TrackInfo("T", "A"), null, null)
        assertEquals("T", Mp4Tags.read(tagged)["©nam"])
        val info = Mp4Defrag.inspect(tagged)
        val joined = samples.fold(ByteArray(0)) { a, b -> a + b }
        assertArrayEquals(joined, tagged.readBytes().copyOfRange(info.chunkOffset.toInt(), info.chunkOffset.toInt() + joined.size))
    }
}
