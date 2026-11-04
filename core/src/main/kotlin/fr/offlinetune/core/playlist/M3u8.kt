package fr.offlinetune.core.playlist

/** One playlist line: [path] relative to the playlist's folder, '/' separators. */
data class M3uEntry(val path: String, val durationSec: Int?, val label: String?)

/** Extended M3U (UTF-8) as read by Poweramp, VLC and most players. */
object M3u8 {
    fun write(entries: List<M3uEntry>): String = buildString {
        append("#EXTM3U\n")
        for (e in entries) {
            append("#EXTINF:").append(e.durationSec ?: -1).append(',').append(e.label ?: "").append('\n')
            append(e.path).append('\n')
        }
    }

    fun parse(text: String): List<M3uEntry> {
        val out = mutableListOf<M3uEntry>()
        var duration: Int? = null
        var label: String? = null
        for (raw in text.removePrefix("﻿").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF:") -> {
                    val body = line.removePrefix("#EXTINF:")
                    duration = body.substringBefore(',').trim().toIntOrNull()?.takeIf { it >= 0 }
                    label = if (',' in body) body.substringAfter(',') else ""
                }
                line.startsWith("#") -> Unit
                else -> {
                    out += M3uEntry(line.replace('\\', '/'), duration, label)
                    duration = null; label = null
                }
            }
        }
        return out
    }

    /** Relative path from a folder to a file, both given as segments from the music root. */
    fun relative(fromDir: List<String>, toFile: List<String>): String {
        var common = 0
        while (common < fromDir.size && common < toFile.size - 1 && fromDir[common] == toFile[common]) common++
        val ups = List(fromDir.size - common) { ".." }
        return (ups + toFile.drop(common)).joinToString("/")
    }

    /** Segments from the music root of [rel] seen from [fromDir]. */
    fun resolve(fromDir: List<String>, rel: String): List<String> {
        val out = fromDir.toMutableList()
        for (seg in rel.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                else -> out += seg
            }
        }
        return out
    }
}
