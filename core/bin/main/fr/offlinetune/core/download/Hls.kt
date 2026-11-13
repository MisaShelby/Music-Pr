package fr.offlinetune.core.download

import okhttp3.HttpUrl.Companion.toHttpUrl

data class HlsPlaylist(val initUrl: String?, val segments: List<String>, val totalSec: Double)

object Hls {
    private val MAP_URI = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""")

    fun parse(text: String, playlistUrl: String): HlsPlaylist {
        val base = playlistUrl.toHttpUrl()
        fun abs(u: String) = base.resolve(u)?.toString() ?: u
        var init: String? = null
        var total = 0.0
        val segments = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MAP") -> MAP_URI.find(line)?.let { init = abs(it.groupValues[1]) }
                line.startsWith("#EXTINF:") -> total += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                line.startsWith("#") -> Unit
                else -> segments += abs(line)
            }
        }
        return HlsPlaylist(init, segments, total)
    }

    /** SoundCloud Go+ previews: the stream is much shorter than the announced track. */
    fun isPreview(streamSec: Double, announcedSec: Int): Boolean =
        streamSec < 0.6 * announcedSec && announcedSec - streamSec > 20
}
