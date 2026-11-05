package fr.offlinetune.core.lyrics

import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.TrackInfo

object LrcFormat {
    private val STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Full .lrc file: [ti:] [ar:] [al:] [length:] headers, then the synced lines (or the plain text). */
    fun build(info: TrackInfo, lyrics: LyricsResult): String = buildString {
        append("[ti:${info.title}]\n")
        append("[ar:${info.artist}]\n")
        info.album?.takeIf { it.isNotBlank() }?.let { append("[al:$it]\n") }
        info.durationSec?.let { append("[length:%02d:%02d]\n".format(it / 60, it % 60)) }
        val body = (lyrics.synced ?: lyrics.plain ?: "").trimEnd()
        if (body.isNotEmpty()) append(body.replace("\r\n", "\n")).append('\n')
    }

    /** Timed lines sorted by time (ms). Empty when the text has no timestamps (= plain lyrics). */
    fun parse(lrc: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        for (line in lrc.lineSequence()) {
            val stamps = mutableListOf<Long>()
            var rest = line.trim()
            while (true) {
                val m = STAMP.matchAt(rest, 0) ?: break
                val (mm, ss, frac) = m.destructured
                val ms = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.toInt() }
                stamps += mm.toLong() * 60_000 + ss.toLong() * 1000 + ms
                rest = rest.substring(m.range.last + 1)
            }
            stamps.forEach { out += it to rest.trim() }
        }
        return out.sortedBy { it.first }
    }

    /** First non-empty lines, timestamps removed. */
    fun preview(lyrics: LyricsResult, lines: Int = 4): List<String> {
        val synced = lyrics.synced?.let { parse(it).map { p -> p.second } }
        val text = synced ?: lyrics.plain?.lines() ?: emptyList()
        return text.map { it.trim() }.filter { it.isNotEmpty() }.take(lines)
    }
}
