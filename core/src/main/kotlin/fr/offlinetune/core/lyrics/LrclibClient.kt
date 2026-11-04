package fr.offlinetune.core.lyrics

import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.NotFound
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.math.abs

fun interface LyricsProvider {
    suspend fun find(info: TrackInfo): LyricsResult?
}

/** LRCLIB (https://lrclib.net), free API without key. */
class LrclibClient(private val base: String = "https://lrclib.net") : LyricsProvider {
    private val headers = mapOf("User-Agent" to "OfflineTune 1.0 (https://github.com/offlinetune)")

    override suspend fun find(info: TrackInfo): LyricsResult? {
        exact(info)?.let { return it }
        return search(info)
    }

    private suspend fun exact(info: TrackInfo): LyricsResult? {
        val q = buildString {
            append("artist_name=${enc(Matcher.firstArtistOf(info.artist))}&track_name=${enc(info.title)}")
            info.album?.let { append("&album_name=${enc(it)}") }
            info.durationSec?.let { append("&duration=$it") }
        }
        val json = try { parseJson(Http.getText("$base/api/get?$q", headers)) } catch (e: NotFound) { return null }
        return json.toResult()
    }

    private suspend fun search(info: TrackInfo): LyricsResult? {
        val q = "track_name=${enc(info.title)}&artist_name=${enc(Matcher.firstArtistOf(info.artist))}"
        val list = (parseJson(Http.getText("$base/api/search?$q", headers)) as? JsonArray) ?: return null
        val wantTitle = Matcher.normalize(info.title)
        return list.asSequence()
            .filter { Matcher.normalize(it.str("trackName") ?: "") == wantTitle }
            .filter { r ->
                val d = (r as? kotlinx.serialization.json.JsonObject)?.get("duration")?.jsonPrimitive?.doubleOrNull
                info.durationSec == null || d == null || abs(d - info.durationSec) <= 5
            }
            .mapNotNull { it.toResult() }
            .sortedByDescending { it.synced != null }
            .firstOrNull()
    }

    private fun JsonElement.toResult(): LyricsResult? {
        val synced = str("syncedLyrics")
        val plain = str("plainLyrics")
        if (synced == null && plain == null) return null
        return LyricsResult(synced, plain)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
