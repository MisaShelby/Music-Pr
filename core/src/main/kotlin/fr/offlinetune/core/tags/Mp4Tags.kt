package fr.offlinetune.core.tags

import fr.offlinetune.core.model.TrackInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * iTunes-style tags (moov/udta/meta/ilst) for .m4a files, written without touching the audio.
 * When moov sits before mdat, stco/co64 chunk offsets are shifted by the size change.
 * Fragmented files (SoundCloud HLS, YouTube DASH) use moof-relative offsets and need no shift.
 */
object Mp4Tags {
    private val L1 = Charsets.ISO_8859_1

    private data class Box(val type: String, val start: Int, val headerLen: Int, val size: Int) {
        val bodyStart get() = start + headerLen
        val end get() = start + size
    }

    private fun children(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            val bb = ByteBuffer.wrap(data)
            var size = bb.getInt(p).toLong() and 0xFFFFFFFFL
            val type = String(data, p + 4, 4, L1)
            var header = 8
            if (size == 1L) { size = bb.getLong(p + 8); header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(type, p, header, size.toInt())
            p += size.toInt()
        }
        return out
    }

    fun write(input: File, output: File, info: TrackInfo, cover: ByteArray?, lyrics: String?) {
        val data = input.readBytes()
        val top = children(data, 0, data.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw IllegalArgumentException("no moov box")
        val firstMdat = top.firstOrNull { it.type == "mdat" }

        val moovKids = children(data, moov.bodyStart, moov.end)
        // A malformed box would make us drop the rest of moov (tracks!): refuse instead.
        if ((moovKids.lastOrNull()?.end ?: moov.bodyStart) != moov.end) throw IllegalArgumentException("malformed moov")
        val kept = moovKids.filter { it.type != "udta" }
        val body = ByteArrayOutputStream()
        kept.forEach { body.write(data, it.start, it.size) }
        // Keep every existing item we don't rewrite (cover/lyrics only when none are given), and other udta children.
        val oldItems = oldIlstItems(data, moov)
        val rewritten = setOf("©nam", "©ART", "©alb", "aART", "©day", "©gen", "trkn", "©too") +
            (if (cover != null) setOf("covr") else emptySet()) + (if (lyrics != null) setOf("©lyr") else emptySet())
        val oldUdta = moovKids.firstOrNull { it.type == "udta" }
        val otherUdta = oldUdta?.let { u -> children(data, u.bodyStart, u.end).filter { it.type != "meta" }.map { data.copyOfRange(it.start, it.end) } } ?: emptyList()
        body.write(udta(info, cover, lyrics, keep = oldItems.filterKeys { it !in rewritten }.values.toList(), otherUdta = otherUdta))
        val newMoov = box("moov", body.toByteArray())

        val delta = newMoov.size - moov.size
        if (firstMdat != null && firstMdat.start > moov.start && delta != 0) shiftChunkOffsets(newMoov, 8, newMoov.size, delta)

        output.outputStream().buffered().use { out ->
            out.write(data, 0, moov.start)
            out.write(newMoov)
            out.write(data, moov.end, data.size - moov.end)
        }
    }

    private fun shiftChunkOffsets(moov: ByteArray, from: Int, to: Int, delta: Int) {
        for (b in children(moov, from, to)) {
            when (b.type) {
                "trak", "mdia", "minf", "stbl" -> shiftChunkOffsets(moov, b.bodyStart, b.end, delta)
                "stco" -> {
                    val bb = ByteBuffer.wrap(moov)
                    val n = bb.getInt(b.bodyStart + 4)
                    for (i in 0 until n) { val o = b.bodyStart + 8 + 4 * i; bb.putInt(o, bb.getInt(o) + delta) }
                }
                "co64" -> {
                    val bb = ByteBuffer.wrap(moov)
                    val n = bb.getInt(b.bodyStart + 4)
                    for (i in 0 until n) { val o = b.bodyStart + 8 + 8 * i; bb.putLong(o, bb.getLong(o) + delta) }
                }
            }
        }
    }

    private fun oldIlstItems(data: ByteArray, moov: Box): Map<String, ByteArray> {
        val udta = children(data, moov.bodyStart, moov.end).firstOrNull { it.type == "udta" } ?: return emptyMap()
        val meta = children(data, udta.bodyStart, udta.end).firstOrNull { it.type == "meta" } ?: return emptyMap()
        val ilst = children(data, meta.bodyStart + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return emptyMap()
        return children(data, ilst.bodyStart, ilst.end).associate { it.type to data.copyOfRange(it.start, it.end) }
    }

    private fun udta(info: TrackInfo, cover: ByteArray?, lyrics: String?, keep: List<ByteArray> = emptyList(), otherUdta: List<ByteArray> = emptyList()): ByteArray {
        val items = ByteArrayOutputStream()
        keep.forEach { items.write(it) }
        fun text(name: String, v: String?) { if (!v.isNullOrBlank()) items.write(item(name, 1, v.toByteArray(Charsets.UTF_8))) }
        text("©nam", info.title)
        text("©ART", info.artist)
        text("©alb", info.album)
        text("aART", info.albumArtist)
        text("©day", info.year?.toString())
        text("©gen", info.genre)
        info.trackNumber?.let { n -> items.write(item("trkn", 0, byteArrayOf(0, 0, (n shr 8).toByte(), n.toByte(), 0, 0, 0, 0))) }
        cover?.let {
            val png = it.size > 3 && it[0] == 0x89.toByte() && it[1] == 'P'.code.toByte()
            items.write(item("covr", if (png) 14 else 13, it))
        }
        text("©lyr", lyrics)
        text("©too", "OfflineTune")
        val hdlr = box("hdlr", ByteArray(8) + "mdirappl".toByteArray(L1) + ByteArray(9))
        val meta = box("meta", ByteArray(4) + hdlr + box("ilst", items.toByteArray()))
        return box("udta", otherUdta.fold(ByteArray(0)) { a, b -> a + b } + meta)
    }

    private fun item(name: String, type: Int, payload: ByteArray) =
        box(name, box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array() + payload))

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(L1)).put(body).array()

    /** Reads ilst items back: text values, "trkn" as the track number, binary items as "". */
    fun read(file: File): Map<String, String> {
        val data = file.readBytes()
        val moov = children(data, 0, data.size).firstOrNull { it.type == "moov" } ?: return emptyMap()
        val udta = children(data, moov.bodyStart, moov.end).firstOrNull { it.type == "udta" } ?: return emptyMap()
        val meta = children(data, udta.bodyStart, udta.end).firstOrNull { it.type == "meta" } ?: return emptyMap()
        val ilst = children(data, meta.bodyStart + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return emptyMap()
        return children(data, ilst.bodyStart, ilst.end).associate { item ->
            val d = children(data, item.bodyStart, item.end).first { it.type == "data" }
            val type = ByteBuffer.wrap(data).getInt(d.bodyStart)
            val payload = data.copyOfRange(d.bodyStart + 8, d.end)
            item.type to when {
                type == 1 -> String(payload, Charsets.UTF_8)
                item.type == "trkn" -> (((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)).toString()
                else -> ""
            }
        }
    }

    /** First stco/co64 entry of the first track (test helper). */
    fun firstChunkOffset(file: File): Long {
        val data = file.readBytes()
        fun find(from: Int, to: Int): Long? {
            for (b in children(data, from, to)) {
                when (b.type) {
                    "moov", "trak", "mdia", "minf", "stbl" -> find(b.bodyStart, b.end)?.let { return it }
                    "stco" -> return ByteBuffer.wrap(data).getInt(b.bodyStart + 8).toLong() and 0xFFFFFFFFL
                    "co64" -> return ByteBuffer.wrap(data).getLong(b.bodyStart + 8)
                }
            }
            return null
        }
        return find(0, data.size) ?: -1
    }
}
