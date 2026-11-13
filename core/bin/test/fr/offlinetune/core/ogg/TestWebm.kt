package fr.offlinetune.core.ogg

import java.io.ByteArrayOutputStream

/** Test-only minimal WebM (Matroska) writer: one Opus track, SimpleBlocks in one cluster. */
object TestWebm {
    fun opusHead(channels: Int = 2, preSkip: Int = 312): ByteArray = ByteArrayOutputStream().apply {
        write("OpusHead".toByteArray()); write(1); write(channels)
        write(preSkip and 0xFF); write(preSkip shr 8)
        val rate = 48000
        write(rate and 0xFF); write((rate shr 8) and 0xFF); write((rate shr 16) and 0xFF); write(rate shr 24)
        write(0); write(0); write(0)
    }.toByteArray()

    /** Fake Opus packets: TOC 0xFC = CELT FB 20 ms, 1 frame (960 samples). */
    fun packets(n: Int, size: Int = 120): List<ByteArray> = List(n) { i -> ByteArray(size) { j -> if (j == 0) 0xFC.toByte() else (i + j).toByte() } }

    fun build(head: ByteArray, packets: List<ByteArray>, unknownSizes: Boolean = true): ByteArray {
        val ebml = el(0x1A45DFA3, el(0x4282, "webm".toByteArray()))
        val track = el(0xAE, el(0xD7, byteArrayOf(1)) + el(0x86, "A_OPUS".toByteArray()) + el(0x63A2, head))
        val tracks = el(0x1654AE6B, track)
        val blocks = packets.mapIndexed { i, p ->
            val tc = i * 20
            el(0xA3, byteArrayOf(0x81.toByte(), (tc shr 8).toByte(), tc.toByte(), 0x80.toByte()) + p)
        }.fold(ByteArray(0)) { a, b -> a + b }
        val ignored = el(0x1549A966, el(0x2AD7B1, byteArrayOf(0x0F, 0x42, 0x40))) // Info, skipped
        val clusterBody = el(0xE7, byteArrayOf(0)) + blocks
        val cluster = if (unknownSizes) id(0x1F43B675) + UNKNOWN + clusterBody else el(0x1F43B675, clusterBody)
        val segBody = ignored + tracks + cluster
        val segment = if (unknownSizes) id(0x18538067) + UNKNOWN + segBody else el(0x18538067, segBody)
        return ebml + segment
    }

    private val UNKNOWN = byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

    private fun id(v: Int): ByteArray {
        val n = when { v > 0xFFFFFF -> 4; v > 0xFFFF -> 3; v > 0xFF -> 2; else -> 1 }
        return ByteArray(n) { (v shr (8 * (n - 1 - it))).toByte() }
    }

    /** 4-byte EBML vint (marker 0x10). */
    private fun size(len: Int): ByteArray = byteArrayOf(0x10, (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())

    private fun el(idv: Int, body: ByteArray) = id(idv) + size(body.size) + body
}
