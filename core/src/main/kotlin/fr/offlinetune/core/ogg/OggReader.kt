package fr.offlinetune.core.ogg

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

data class OggInfo(
    val pages: Int,
    val crcOk: Boolean,
    val preSkip: Int,
    val durationSec: Double,
    val comments: Map<String, List<String>>,
    val hasPicture: Boolean,
    val pictureBytes: ByteArray?,
)

/** Reads back an Ogg Opus file: page CRCs, OpusHead pre-skip, OpusTags comments, duration from the last granule. */
object OggReader {
    fun inspect(file: File): OggInfo {
        val data = file.readBytes()
        var pos = 0
        var pages = 0
        var crcOk = true
        var lastGranule = 0L
        val packets = mutableListOf<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        while (pos + 27 <= data.size) {
            if (String(data, pos, 4) != "OggS") { crcOk = false; break }
            val bb = ByteBuffer.wrap(data, pos, 27).order(ByteOrder.LITTLE_ENDIAN)
            val granule = bb.getLong(pos + 6)
            val storedCrc = bb.getInt(pos + 22)
            val nseg = data[pos + 26].toInt() and 0xFF
            val lacing = (0 until nseg).map { data[pos + 27 + it].toInt() and 0xFF }
            val headerLen = 27 + nseg
            val bodyLen = lacing.sum()
            val page = data.copyOfRange(pos, pos + headerLen + bodyLen)
            page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
            if (OggCrc.update(0, page) != storedCrc) crcOk = false
            if (granule != -1L) lastGranule = granule
            if (packets.size < 2) {
                var off = pos + headerLen
                for (l in lacing) {
                    current.write(data, off, l); off += l
                    if (l < 255) { packets += current.toByteArray(); current = java.io.ByteArrayOutputStream() }
                }
            }
            pos += headerLen + bodyLen
            pages++
        }
        val head = packets.getOrNull(0) ?: ByteArray(19)
        val preSkip = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
        val comments = packets.getOrNull(1)?.let { parseTags(it) } ?: emptyMap()
        val pic = comments["METADATA_BLOCK_PICTURE"]?.firstOrNull()?.let { pictureData(Base64.getDecoder().decode(it)) }
        return OggInfo(pages, crcOk, preSkip, (lastGranule - preSkip) / 48000.0, comments, pic != null, pic)
    }

    /**
     * OpusHead and the audio packets (everything after OpusTags), reassembled across pages. Refuses (Unsupported)
     * anything that would not survive a rewrite: non-Opus streams (Vorbis...), several logical streams or chained
     * files, and damaged files.
     */
    fun packets(file: File): Pair<ByteArray, List<ByteArray>> {
        val data = file.readBytes()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val all = mutableListOf<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var pos = 0
        var serial: Int? = null
        fun damaged(): Nothing = throw fr.offlinetune.core.model.Unsupported("ce fichier Ogg endommagé")
        while (pos < data.size) {
            if (pos + 27 > data.size || String(data, pos, 4, Charsets.ISO_8859_1) != "OggS") damaged()
            val type = data[pos + 5].toInt()
            val s = bb.getInt(pos + 14)
            if (serial == null) serial = s
            else if (s != serial || type and 2 != 0) throw fr.offlinetune.core.model.Unsupported("les fichiers Ogg à plusieurs flux")
            val nseg = data[pos + 26].toInt() and 0xFF
            if (pos + 27 + nseg > data.size) damaged()
            var off = pos + 27 + nseg
            for (i in 0 until nseg) {
                val l = data[pos + 27 + i].toInt() and 0xFF
                if (off + l > data.size) damaged()
                current.write(data, off, l); off += l
                if (l < 255) { all += current.toByteArray(); current = java.io.ByteArrayOutputStream() }
            }
            pos = off
        }
        fun starts(p: ByteArray?, magic: String) = p != null && p.size >= 8 && String(p, 0, 8, Charsets.ISO_8859_1) == magic
        if (!starts(all.getOrNull(0), "OpusHead") || !starts(all.getOrNull(1), "OpusTags")) {
            throw fr.offlinetune.core.model.Unsupported("la modification des fichiers Ogg qui ne sont pas en Opus")
        }
        return all[0] to all.drop(2)
    }

    /** Vorbis comments of an Ogg Opus file (null if unreadable). */
    fun comments(file: File): Map<String, List<String>>? = runCatching { inspect(file).comments }.getOrNull()

    private fun parseTags(p: ByteArray): Map<String, List<String>> {
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val vendorLen = bb.int; bb.position(bb.position() + vendorLen)
        val n = bb.int
        val out = mutableMapOf<String, MutableList<String>>()
        repeat(n) {
            val len = bb.int
            val s = String(p, bb.position(), len, Charsets.UTF_8); bb.position(bb.position() + len)
            out.getOrPut(s.substringBefore('=').uppercase()) { mutableListOf() } += s.substringAfter('=')
        }
        return out
    }

    private fun pictureData(block: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(block).order(ByteOrder.BIG_ENDIAN)
        bb.int
        val mimeLen = bb.int; bb.position(bb.position() + mimeLen)
        val descLen = bb.int; bb.position(bb.position() + descLen + 16)
        val len = bb.int
        return block.copyOfRange(bb.position(), bb.position() + len)
    }
}
