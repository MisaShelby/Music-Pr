package fr.offlinetune.core.ogg

import fr.offlinetune.core.model.Unsupported
import java.io.EOFException
import java.io.InputStream

data class WebmOpus(val opusHead: ByteArray, val packets: Sequence<ByteArray>)

/**
 * Minimal Matroska/WebM demuxer for YouTube audio (itag 251): returns the OpusHead (CodecPrivate)
 * and the Opus packets of the A_OPUS track, untouched.
 */
object WebmReader {
    private const val SEGMENT = 0x18538067L
    private const val CLUSTER = 0x1F43B675L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val CODEC_ID = 0x86L
    private const val CODEC_PRIVATE = 0x63A2L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val SIMPLE_BLOCK = 0xA3L
    private val MASTERS = setOf(SEGMENT, CLUSTER, TRACKS, TRACK_ENTRY, BLOCK_GROUP)

    private class Reader(private val input: InputStream) {
        var pos = 0L
        fun byte(): Int { val b = input.read(); if (b < 0) throw EOFException(); pos++; return b }
        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n); var off = 0
            while (off < n) { val r = input.read(out, off, n - off); if (r < 0) throw EOFException(); off += r }
            pos += n; return out
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val s = input.skip(left)
                if (s <= 0) { byte(); left--; continue }
                left -= s; pos += s
            }
        }
        /** EBML element ID (marker bits kept). */
        fun id(): Long {
            val first = byte()
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..4) throw Unsupported("fichier WebM invalide")
            var v = first.toLong()
            repeat(len - 1) { v = (v shl 8) or byte().toLong() }
            return v
        }
        /** EBML size (marker removed); -1 for "unknown size". */
        fun size(): Long {
            val first = byte()
            val len = Integer.numberOfLeadingZeros(first) - 23
            if (len !in 1..8) throw Unsupported("fichier WebM invalide")
            var v = (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            repeat(len - 1) { val b = byte(); v = (v shl 8) or b.toLong(); allOnes = allOnes && b == 0xFF }
            return if (allOnes) -1 else v
        }
    }

    fun read(input: InputStream): WebmOpus {
        val r = Reader(input)
        val codecs = mutableMapOf<Long, String>()
        val privates = mutableMapOf<Long, ByteArray>()
        var curTrack = -1L; var curCodec: String? = null; var curPrivate: ByteArray? = null
        val packets = mutableListOf<Pair<Long, ByteArray>>()

        fun closeTrack() {
            if (curTrack >= 0) { curCodec?.let { codecs[curTrack] = it }; curPrivate?.let { privates[curTrack] = it } }
            curTrack = -1; curCodec = null; curPrivate = null
        }

        try {
            while (true) {
                val id = r.id()
                val size = r.size()
                when (id) {
                    TRACK_ENTRY, CLUSTER -> closeTrack() // then descend
                    in MASTERS -> Unit // descend
                    TRACK_NUMBER -> curTrack = r.bytes(size.toInt()).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
                    CODEC_ID -> curCodec = String(r.bytes(size.toInt())).trimEnd('\u0000')
                    CODEC_PRIVATE -> curPrivate = r.bytes(size.toInt())
                    SIMPLE_BLOCK, BLOCK -> packets += block(r.bytes(size.toInt()))
                    else -> if (size < 0) throw Unsupported("fichier WebM invalide") else r.skip(size)
                }
            }
        } catch (_: EOFException) {
            // end of file
        }
        closeTrack()

        val opusTrack = codecs.entries.firstOrNull { it.value == "A_OPUS" }?.key
            ?: throw Unsupported("flux audio non Opus")
        val head = privates[opusTrack] ?: throw Unsupported("en-tête Opus manquant")
        return WebmOpus(head, packets.asSequence().filter { it.first == opusTrack }.map { it.second })
    }

    private fun block(data: ByteArray): Pair<Long, ByteArray> {
        val first = data[0].toInt() and 0xFF
        val len = Integer.numberOfLeadingZeros(first) - 23
        var track = (first and (0xFF shr len)).toLong()
        for (i in 1 until len) track = (track shl 8) or (data[i].toLong() and 0xFF)
        val flags = data[len + 2].toInt()
        if (flags and 0x06 != 0) throw Unsupported("WebM avec laçage")
        return track to data.copyOfRange(len + 3, data.size)
    }
}
