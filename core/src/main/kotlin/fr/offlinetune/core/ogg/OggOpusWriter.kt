package fr.offlinetune.core.ogg

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.random.Random

/** Vorbis comment block (OpusTags). [picture] is a JPEG embedded as METADATA_BLOCK_PICTURE (front cover). */
data class VorbisComment(
    val vendor: String = "OfflineTune",
    val fields: List<Pair<String, String>>,
    val picture: ByteArray? = null,
    val pictureMime: String = "image/jpeg",
) {
    fun toOpusTags(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray())
        val vendorBytes = vendor.toByteArray()
        out.writeLe32(vendorBytes.size); out.write(vendorBytes)
        val all = fields.filter { it.second.isNotEmpty() }.map { "${it.first.uppercase()}=${it.second}" } +
            listOfNotNull(picture?.let { "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(flacPicture(it, pictureMime)) })
        out.writeLe32(all.size)
        all.forEach { val b = it.toByteArray(); out.writeLe32(b.size); out.write(b) }
        return out.toByteArray()
    }

    companion object {
        /** FLAC METADATA_BLOCK_PICTURE body: type 3 (front cover), mime, empty description, unknown dimensions. */
        fun flacPicture(data: ByteArray, mime: String): ByteArray {
            val m = mime.toByteArray()
            return ByteBuffer.allocate(32 + m.size + data.size).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(3); putInt(m.size); put(m); putInt(0)
                putInt(0); putInt(0); putInt(0); putInt(0)
                putInt(data.size); put(data)
            }.array()
        }
    }
}

private fun OutputStream.writeLe32(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v ushr 24) and 0xFF) }

/** Writes an Ogg Opus stream (RFC 7845) from an OpusHead and raw Opus packets, without re-encoding. */
object OggOpusWriter {
    private const val MAX_PAGE_BODY = 4096

    fun write(opusHead: ByteArray, packets: Sequence<ByteArray>, tags: VorbisComment, out: OutputStream) {
        val pager = Pager(out, Random.nextInt())
        pager.add(opusHead, granule = 0, bos = true)
        pager.flush()
        pager.add(tags.toOpusTags(), granule = 0)
        pager.flush()
        var granule = 0L
        var pending: ByteArray? = null
        for (p in packets) {
            pending?.let { pager.add(it, granule); if (pager.bodySize >= MAX_PAGE_BODY) pager.flush() }
            granule += samplesPerPacket(p)
            pending = p
        }
        pending?.let { pager.add(it, granule) }
        pager.flush(eos = true)
    }

    /** Samples at 48 kHz in one Opus packet, from its TOC byte (RFC 6716 §3.1). */
    fun samplesPerPacket(p: ByteArray): Int {
        if (p.isEmpty()) return 0
        val toc = p[0].toInt() and 0xFF
        val config = toc shr 3
        val frame = when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
            config < 16 -> intArrayOf(480, 960)[config % 2]
            else -> intArrayOf(120, 240, 480, 960)[config % 4]
        }
        val count = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (p.size > 1) p[1].toInt() and 0x3F else 0
        }
        return frame * count
    }

    /** Accumulates packets into pages; splits packets across pages when the 255-segment limit is hit. */
    private class Pager(private val out: OutputStream, private val serial: Int) {
        private val lacing = ArrayList<Int>()
        private val body = ByteArrayOutputStream()
        private var seq = 0
        private var pageGranule = -1L
        private var continued = false
        private var bos = false
        val bodySize get() = body.size()

        fun add(packet: ByteArray, granule: Long, bos: Boolean = false) {
            if (bos) this.bos = true
            var off = 0
            while (true) {
                val remaining = packet.size - off
                val seg = minOf(255, remaining)
                if (lacing.size == 255) {
                    val midPacket = lacing.last() == 255
                    writePage(eos = false)
                    continued = midPacket
                }
                lacing += seg
                body.write(packet, off, seg)
                off += seg
                if (seg < 255) break // packet ends here (a final 0 lacing value terminates multiples of 255)
            }
            pageGranule = granule
        }

        fun flush(eos: Boolean = false) {
            if (lacing.isEmpty() && !eos) return
            writePage(eos)
        }

        private fun writePage(eos: Boolean) {
            // granule = -1 when no packet completes on this page
            val completes = lacing.isNotEmpty() && lacing.last() < 255
            val header = ByteBuffer.allocate(27 + lacing.size).order(ByteOrder.LITTLE_ENDIAN)
            header.put("OggS".toByteArray()); header.put(0)
            header.put(((if (continued) 1 else 0) or (if (bos) 2 else 0) or (if (eos) 4 else 0)).toByte())
            header.putLong(if (completes) pageGranule else -1L)
            header.putInt(serial); header.putInt(seq++); header.putInt(0)
            header.put(lacing.size.toByte())
            lacing.forEach { header.put(it.toByte()) }
            val h = header.array()
            val b = body.toByteArray()
            val crc = OggCrc.update(OggCrc.update(0, h), b)
            ByteBuffer.wrap(h, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
            out.write(h); out.write(b)
            lacing.clear(); body.reset()
            continued = false; bos = false
        }
    }
}

/** Ogg CRC-32: polynomial 0x04C11DB7, init 0, no reflection, no final xor. */
object OggCrc {
    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun update(crc: Int, data: ByteArray, off: Int = 0, len: Int = data.size): Int {
        var c = crc
        for (i in off until off + len) c = (c shl 8) xor table[((c ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF]
        return c
    }
}
