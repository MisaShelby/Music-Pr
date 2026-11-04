package fr.offlinetune.core.tags

import fr.offlinetune.core.model.TrackInfo
import java.io.ByteArrayOutputStream
import java.io.File

/** Replaces any ID3 tags of an MP3 with a fresh ID3v2.3 tag (UTF-16 text, front cover, unsynced lyrics). */
object Id3Writer {
    /** Frames this writer manages; every other frame of an existing tag is kept as it was. */
    private val MANAGED = setOf("TIT2", "TPE1", "TALB", "TPE2", "TYER", "TDRC", "TRCK", "TCON", "APIC", "USLT")

    fun rewrite(mp3In: File, mp3Out: File, info: TrackInfo, cover: ByteArray?, lyrics: String?, extraFrames: List<Pair<String, String>> = emptyList()) {
        val data = mp3In.readBytes()
        val old = oldFrames(data)
        val kept = old.filter { it.first == "APIC" || it.first == "USLT" }.toMap()
        var start = 0
        // Skip one or more leading ID3v2 tags.
        while (data.size >= start + 10 && data[start] == 'I'.code.toByte() && data[start + 1] == 'D'.code.toByte() && data[start + 2] == '3'.code.toByte()) {
            val footer = if (data[start + 5].toInt() and 0x10 != 0) 10 else 0
            start += 10 + synchsafe(data, start + 6) + footer
        }
        var end = data.size
        if (end - start >= 128 && String(data, end - 128, 3, Charsets.ISO_8859_1) == "TAG") end -= 128

        val frames = ByteArrayOutputStream()
        // Unmanaged frames (disc number, composer, ReplayGain, comments...) are carried over unchanged.
        old.filter { it.first !in MANAGED }.forEach { frames.write(it.second) }
        extraFrames.forEach { (id, v) -> frames.write(frame(id, byteArrayOf(1) + utf16(v))) }
        fun text(id: String, value: String?) { if (!value.isNullOrBlank()) frames.write(frame(id, byteArrayOf(1) + utf16(value))) }
        text("TIT2", info.title)
        text("TPE1", info.artist)
        text("TALB", info.album)
        text("TPE2", info.albumArtist)
        text("TYER", info.year?.toString())
        text("TRCK", info.trackNumber?.toString())
        text("TCON", info.genre)
        if (cover == null) kept["APIC"]?.let { frames.write(it) }
        if (lyrics == null) kept["USLT"]?.let { frames.write(it) }
        if (cover != null) {
            val mime = if (cover.size > 3 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
            frames.write(frame("APIC", byteArrayOf(0) + mime.toByteArray() + byteArrayOf(0, 3, 0) + cover))
        }
        if (!lyrics.isNullOrBlank()) {
            frames.write(frame("USLT", byteArrayOf(1) + "fra".toByteArray() + utf16("") + utf16(lyrics)))
        }
        val body = frames.toByteArray()
        mp3Out.outputStream().buffered().use { out ->
            out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            out.write(toSynchsafe(body.size))
            out.write(body)
            out.write(data, start, end - start)
        }
    }

    /** Test/debug helper: frame id → decoded text value (text frames) or "" (binary frames). */
    fun parseFrames(tag: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var p = 0
        while (p + 10 <= tag.size && tag[p] != 0.toByte()) {
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val size = ((tag[p + 4].toInt() and 0xFF) shl 24) or ((tag[p + 5].toInt() and 0xFF) shl 16) or
                ((tag[p + 6].toInt() and 0xFF) shl 8) or (tag[p + 7].toInt() and 0xFF)
            val content = tag.copyOfRange(p + 10, p + 10 + size)
            out[id] = if (id.startsWith("T") && content[0].toInt() == 1) {
                String(content, 1, content.size - 1, Charsets.UTF_16).trimEnd('\u0000')
            } else ""
            p += 10 + size
        }
        return out
    }

    /**
     * Frames of an existing ID3v2.3/2.4 tag, re-encoded as v2.3 frames. Anything that cannot be copied safely is
     * dropped rather than corrupted: tags with unsynchronisation, v2.4 frames with format flags, UTF-8 text.
     */
    private fun oldFrames(data: ByteArray): List<Pair<String, ByteArray>> {
        if (data.size < 10 || String(data, 0, 3, Charsets.ISO_8859_1) != "ID3") return emptyList()
        val version = data[3].toInt()
        val flags = data[5].toInt()
        if (version !in 3..4 || flags and 0x80 != 0) return emptyList()
        val end = minOf(10 + synchsafe(data, 6), data.size)
        var p = 10
        if (flags and 0x40 != 0 && p + 4 <= end) {
            p += if (version == 4) synchsafe(data, p) else 4 + be32(data, p)
        }
        val out = mutableListOf<Pair<String, ByteArray>>()
        while (p + 10 <= end && data[p] != 0.toByte()) {
            val id = String(data, p, 4, Charsets.ISO_8859_1)
            val size = if (version == 4) synchsafe(data, p + 4) else be32(data, p + 4)
            if (size <= 0 || p + 10 + size > end) break
            val content = data.copyOfRange(p + 10, p + 10 + size)
            val formatFlags = data[p + 9].toInt()
            val utf8 = (id.startsWith("T") || id == "USLT" || id == "COMM" || id == "APIC") && content.isNotEmpty() && content[0].toInt() >= 2
            if (!(version == 4 && formatFlags != 0) && !utf8) out += id to frame(id, content)
            p += 10 + size
        }
        return out
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun utf16(s: String): ByteArray = s.toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) // BOM + text + terminator

    private fun frame(id: String, content: ByteArray): ByteArray {
        val s = content.size
        return id.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf((s ushr 24).toByte(), (s ushr 16).toByte(), (s ushr 8).toByte(), s.toByte(), 0, 0) + content
    }

    private fun synchsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun toSynchsafe(v: Int) = byteArrayOf(((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(), ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte())
}
