package fr.offlinetune.core.tags

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Turns a fragmented MP4 (init segment + moof/mdat pairs, as served by SoundCloud HLS) into a plain MP4
 * with full sample tables, so players get a duration and can seek. Samples are copied byte for byte.
 * Single audio track only.
 */
object Mp4Defrag {
    private val L1 = Charsets.ISO_8859_1

    private data class Box(val type: String, val start: Int, val header: Int, val size: Int) {
        val body get() = start + header
        val end get() = start + size
    }

    private fun children(d: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        val bb = ByteBuffer.wrap(d)
        while (p + 8 <= to) {
            var size = bb.getInt(p).toLong() and 0xFFFFFFFFL
            var h = 8
            if (size == 1L) { size = bb.getLong(p + 8); h = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < h || p + size > to) break
            out += Box(String(d, p + 4, 4, L1), p, h, size.toInt())
            p += size.toInt()
        }
        return out
    }

    private fun List<Box>.one(t: String) = firstOrNull { it.type == t } ?: throw IllegalArgumentException("missing $t")

    fun isFragmented(file: File): Boolean {
        val d = file.readBytes()
        return children(d, 0, d.size).any { it.type == "moof" }
    }

    private class Sample(val offset: Int, val size: Int, val duration: Int)

    fun defragment(input: File, output: File) {
        val d = input.readBytes()
        val bb = ByteBuffer.wrap(d)
        val top = children(d, 0, d.size)
        val moov = top.one("moov")
        val moovKids = children(d, moov.body, moov.end)
        val traks = moovKids.filter { it.type == "trak" }
        require(traks.size == 1) { "single track only" }
        val trex = moovKids.firstOrNull { it.type == "mvex" }?.let { children(d, it.body, it.end).firstOrNull { b -> b.type == "trex" } }
        val defDuration = trex?.let { bb.getInt(it.body + 12) } ?: 0
        val defSize = trex?.let { bb.getInt(it.body + 16) } ?: 0

        // Collect samples from every moof/traf/trun.
        val samples = mutableListOf<Sample>()
        for (moof in top.filter { it.type == "moof" }) {
            for (traf in children(d, moof.body, moof.end).filter { it.type == "traf" }) {
                val tk = children(d, traf.body, traf.end)
                val tfhd = tk.one("tfhd")
                val tf = bb.getInt(tfhd.body) and 0xFFFFFF
                var q = tfhd.body + 8
                var base = moof.start.toLong()
                if (tf and 0x1 != 0) { base = bb.getLong(q); q += 8 }
                if (tf and 0x2 != 0) q += 4
                val tDur = if (tf and 0x8 != 0) bb.getInt(q).also { q += 4 } else defDuration
                val tSize = if (tf and 0x10 != 0) bb.getInt(q).also { q += 4 } else defSize
                var next = base
                for (trun in tk.filter { it.type == "trun" }) {
                    val rf = bb.getInt(trun.body) and 0xFFFFFF
                    val n = bb.getInt(trun.body + 4)
                    var r = trun.body + 8
                    var pos = if (rf and 0x1 != 0) (base + bb.getInt(r)).also { r += 4 } else next
                    if (rf and 0x4 != 0) r += 4
                    repeat(n) {
                        val dur = if (rf and 0x100 != 0) bb.getInt(r).also { r += 4 } else tDur
                        val size = if (rf and 0x200 != 0) bb.getInt(r).also { r += 4 } else tSize
                        if (rf and 0x400 != 0) r += 4
                        if (rf and 0x800 != 0) r += 4
                        samples += Sample(pos.toInt(), size, dur)
                        pos += size
                    }
                    next = pos
                }
            }
        }
        require(samples.isNotEmpty()) { "no samples" }

        val mediaDuration = samples.sumOf { it.duration.toLong() }
        val mvhd = moovKids.one("mvhd")
        val trak = traks[0]
        val mdia = children(d, trak.body, trak.end).one("mdia")
        val mdhd = children(d, mdia.body, mdia.end).one("mdhd")
        val mediaScale = bb.getInt(mdhd.body + if (d[mdhd.body].toInt() == 1) 20 else 12)
        val movieScale = bb.getInt(mvhd.body + if (d[mvhd.body].toInt() == 1) 20 else 12)
        val movieDuration = mediaDuration * movieScale / mediaScale

        // Sample tables: run-length durations, all sizes, one chunk holding every sample.
        val stts = ByteArrayOutputStream().apply {
            val runs = mutableListOf<Pair<Int, Int>>()
            samples.forEach { s -> if (runs.isNotEmpty() && runs.last().second == s.duration) runs[runs.lastIndex] = runs.last().first + 1 to s.duration else runs += 1 to s.duration }
            write(ints(0, runs.size)); runs.forEach { write(ints(it.first, it.second)) }
        }.toByteArray()
        val stsz = ints(0, 0, samples.size) + ints(*samples.map { it.size }.toIntArray())
        val stsc = ints(0, 1, 1, samples.size, 1)

        fun rebuild(b: Box, chunkOffset: Int): ByteArray = when (b.type) {
            "mvex" -> ByteArray(0)
            "mvhd" -> patchDuration(d, b, movieDuration, v0Off = 16, v1Off = 24)
            "tkhd" -> patchDuration(d, b, movieDuration, v0Off = 20, v1Off = 28)
            "mdhd" -> patchDuration(d, b, mediaDuration, v0Off = 16, v1Off = 24)
            "elst" -> patchEdit(d, b, movieDuration)
            "stts" -> box("stts", stts)
            "stsz" -> box("stsz", stsz)
            "stsc" -> box("stsc", stsc)
            "stco" -> box("stco", ints(0, 1, chunkOffset))
            "co64", "stss" -> ByteArray(0)
            "trak", "mdia", "minf", "stbl", "edts" -> box(b.type, children(d, b.body, b.end).fold(ByteArray(0)) { a, c -> a + rebuild(c, chunkOffset) })
            else -> d.copyOfRange(b.start, b.end)
        }
        fun newMoov(chunkOffset: Int) = box("moov", moovKids.fold(ByteArray(0)) { a, c -> a + rebuild(c, chunkOffset) })

        val ftyp = top.firstOrNull { it.type == "ftyp" }?.let { d.copyOfRange(it.start, it.end) } ?: ByteArray(0)
        val moovSize = newMoov(0).size
        val dataStart = ftyp.size + moovSize + 8
        val dataLen = samples.sumOf { it.size.toLong() }
        output.outputStream().buffered().use { out ->
            out.write(ftyp)
            out.write(newMoov(dataStart))
            out.write(ints((8 + dataLen).toInt())); out.write("mdat".toByteArray(L1))
            samples.forEach { out.write(d, it.offset, it.size) }
        }
    }

    private fun patchDuration(d: ByteArray, b: Box, value: Long, v0Off: Int, v1Off: Int): ByteArray {
        val copy = d.copyOfRange(b.start, b.end)
        val bb = ByteBuffer.wrap(copy)
        if (d[b.body].toInt() == 1) bb.putLong(b.header + v1Off, value) else bb.putInt(b.header + v0Off, value.toInt())
        return copy
    }

    /** Edit list entries with a zero segment duration (normal in fragmented files) get the real duration. */
    private fun patchEdit(d: ByteArray, b: Box, movieDuration: Long): ByteArray {
        val copy = d.copyOfRange(b.start, b.end)
        val bb = ByteBuffer.wrap(copy)
        val v1 = copy[b.header].toInt() == 1
        val n = bb.getInt(b.header + 4)
        var p = b.header + 8
        repeat(n) {
            if (v1) { if (bb.getLong(p) == 0L) bb.putLong(p, movieDuration); p += 20 }
            else { if (bb.getInt(p) == 0) bb.putInt(p, movieDuration.toInt()); p += 12 }
        }
        return copy
    }

    private fun ints(vararg v: Int) = ByteBuffer.allocate(4 * v.size).apply { v.forEach { putInt(it) } }.array()
    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(L1)).put(body).array()

    data class Mp4Info(val sampleSizes: List<Int>, val mediaDuration: Long, val movieDuration: Long, val chunkOffset: Long)

    /** Reads back stsz / durations / first chunk offset (tests). */
    fun inspect(file: File): Mp4Info {
        val d = file.readBytes()
        val bb = ByteBuffer.wrap(d)
        val moov = children(d, 0, d.size).one("moov")
        val mk = children(d, moov.body, moov.end)
        val mvhd = mk.one("mvhd")
        val trak = mk.one("trak")
        val mdia = children(d, trak.body, trak.end).one("mdia")
        val mdhd = children(d, mdia.body, mdia.end).one("mdhd")
        val minf = children(d, mdia.body, mdia.end).one("minf")
        val stbl = children(d, minf.body, minf.end).one("stbl")
        val st = children(d, stbl.body, stbl.end)
        val stsz = st.one("stsz")
        val n = bb.getInt(stsz.body + 8)
        val sizes = (0 until n).map { bb.getInt(stsz.body + 12 + 4 * it) }
        fun dur(b: Box, v0: Int, v1: Int) = if (d[b.body].toInt() == 1) bb.getLong(b.body + v1) else bb.getInt(b.body + v0).toLong() and 0xFFFFFFFFL
        return Mp4Info(sizes, dur(mdhd, 16, 24), dur(mvhd, 16, 24), bb.getInt(st.one("stco").body + 8).toLong() and 0xFFFFFFFFL)
    }
}
