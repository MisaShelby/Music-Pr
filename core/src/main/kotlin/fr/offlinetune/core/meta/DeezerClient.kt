package fr.offlinetune.core.meta

import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.model.NotFound
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.SearchHit
import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.int
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

/** Public Deezer API (no key). */
class DeezerClient(
    private val base: String = "https://api.deezer.com",
    private val quotaDelayMs: Long = 1500,
) {

    private suspend fun get(path: String): JsonElement {
        repeat(QUOTA_RETRIES) {
            val json = parseJson(Http.getText("$base$path"))
            if (json.str("error", "type") == null) return json
            // Code 4 = "Quota limit exceeded" (≈ 50 requests / 5 s): wait and retry.
            if (json.int("error", "code") != 4) throw NotFound("Deezer $path")
            delay(quotaDelayMs)
        }
        throw NetworkLost()
    }

    suspend fun track(id: String): TrackInfo {
        val t = get("/track/$id")
        val albumId = t.str("album", "id")
        val album = albumId?.let { runCatching { get("/album/$it") }.getOrNull() }
        return TrackInfo(
            title = t.str("title") ?: throw NotFound("Deezer track $id"),
            artist = t.str("artist", "name") ?: "",
            album = t.str("album", "title"),
            albumArtist = album.str("artist", "name"),
            year = (t.str("release_date") ?: album.str("release_date"))?.take(4)?.toIntOrNull(),
            trackNumber = t.int("track_position"),
            genre = album?.arr("genres", "data")?.firstOrNull().str("name"),
            durationSec = t.int("duration"),
            coverUrl = t.str("album", "cover_xl") ?: album.str("cover_xl"),
            isrc = t.str("isrc"),
        )
    }

    suspend fun byIsrc(isrc: String): TrackInfo? =
        runCatching { get("/track/isrc:$isrc") }.getOrNull()?.str("id")?.let { track(it) }

    suspend fun search(q: String, limit: Int = 20): List<SearchHit> =
        get("/search/track?limit=$limit&q=${enc(q)}").arr("data").map { it.toHit() }

    /** Best catalogue entry for a title/artist, or null when no safe match exists. */
    suspend fun findBest(title: String, artist: String, durationSec: Int?): TrackInfo? {
        val strict = "artist:\"$artist\" track:\"$title\""
        val hits = get("/search/track?limit=10&q=${enc(strict)}").arr("data").map { it.toHit() }
            .ifEmpty { search("$artist $title", 10) }
        val ref = TrackInfo(title, artist, durationSec = durationSec)
        val accepted = Matcher.rank(ref, hits.map { SourceCandidate(it.url, it.title, it.artist, it.durationSec) })
            .filter { it.rejectedReason == null && it.score >= SAFE_SCORE }
        val bestScore = accepted.firstOrNull()?.score ?: return null
        // Same song often exists on compilations: among near-equal matches prefer the artist's own album, then the oldest.
        val details = accepted.filter { it.score >= bestScore - 0.05 }.take(4)
            .mapNotNull { runCatching { track(it.url.substringAfterLast('/')) }.getOrNull() }
        val wanted = Matcher.normalize(Matcher.firstArtistOf(artist))
        return details.sortedWith(
            compareBy<TrackInfo> { Matcher.normalize(it.albumArtist ?: "") != wanted }
                .thenBy { it.year ?: Int.MAX_VALUE },
        ).firstOrNull()
    }

    private fun JsonElement.toHit() = SearchHit(
        kind = Platform.DEEZER,
        url = "https://www.deezer.com/track/${str("id")}",
        title = str("title") ?: "",
        artist = str("artist", "name") ?: "",
        durationSec = int("duration"),
        thumbUrl = str("album", "cover_medium"),
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SAFE_SCORE = 0.75
        private const val QUOTA_RETRIES = 6
    }
}
