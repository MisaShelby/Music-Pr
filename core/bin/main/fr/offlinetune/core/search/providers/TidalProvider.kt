package fr.offlinetune.core.search.providers

import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.long
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.SearchProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Tidal catalogue through the official Open API (JSON:API), with the user's own developer key. Metadata only:
 * the quality shown is what Tidal offers in its own app (from `mediaTags`).
 */
class TidalProvider(
    keys: () -> Pair<String, String>?,
    auth: String = "https://auth.tidal.com",
    private val api: String = "https://openapi.tidal.com",
) : SearchProvider {
    override val provider = Provider.TIDAL
    private val credentials = ClientCredentials("Tidal", "$auth/v1/oauth2/token", keys)

    private suspend fun get(url: String): JsonElement {
        val token = credentials.bearer()
        return parseJson(Http.getText(url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.api+json")))
    }

    override suspend fun search(query: String, limit: Int): List<ProviderHit> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val found = get("$api/v2/searchResults/$q/relationships/tracks?countryCode=FR&include=tracks")
        val tracks = found.arr("included").filter { it.str("type") == "tracks" }.take(limit)
        if (tracks.isEmpty()) return emptyList()

        // Artist and album names come from a second call that includes them.
        val ids = tracks.mapNotNull { it.str("id") }.joinToString(",")
        val details = runCatching { get("$api/v2/tracks?countryCode=FR&filter%5Bid%5D=$ids&include=artists,albums") }.getOrNull()
        val included = details.arr("included").associateBy { "${it.str("type")}:${it.str("id")}" }
        val rel = details.arr("data").associateBy { it.str("id") }

        return tracks.mapNotNull { t ->
            val id = t.str("id") ?: return@mapNotNull null
            val a = t.at2("attributes")
            val r = rel[id]
            val artists = r.arr("relationships", "artists", "data").mapNotNull { included["artists:${it.str("id")}"].str("attributes", "name") }
            val album = r.arr("relationships", "albums", "data").firstOrNull()?.let { included["albums:${it.str("id")}"] }
            ProviderHit(
                provider = provider,
                url = a.arr("externalLinks").firstNotNullOfOrNull { it.str("href") } ?: "https://tidal.com/browse/track/$id",
                title = a.str("title") ?: return@mapNotNull null,
                artist = artists.joinToString(", "),
                album = album.str("attributes", "title"),
                durationSec = a.str("duration")?.let(::isoDuration),
                isrc = a.str("isrc"),
                coverUrl = album.arr("attributes", "imageLinks").maxByOrNull { it.long("meta", "width") ?: 0 }.str("href"),
                quality = quality(a.arr("mediaTags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }),
            )
        }
    }

    private fun JsonElement.at2(key: String): JsonElement? = (this as? kotlinx.serialization.json.JsonObject)?.get(key)

    companion object {
        private val ISO = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""")

        fun isoDuration(s: String): Int? {
            val m = ISO.matchEntire(s) ?: return null
            val (h, mi, se) = m.destructured
            return ((h.toIntOrNull() ?: 0) * 3600 + (mi.toIntOrNull() ?: 0) * 60 + (se.toDoubleOrNull() ?: 0.0)).roundToInt()
        }

        fun quality(tags: List<String>): String = when {
            "HIRES_LOSSLESS" in tags -> "FLAC jusqu'à 24 bit / 192 kHz"
            "LOSSLESS" in tags -> "FLAC 16 bit / 44,1 kHz"
            else -> "AAC 320 kb/s"
        }
    }
}
