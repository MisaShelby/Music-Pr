package fr.offlinetune.core.search.providers

import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.long
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.SearchProvider
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Spotify catalogue through the official Web API, with the user's own developer key. Metadata only. */
class SpotifyProvider(
    keys: () -> Pair<String, String>?,
    accounts: String = "https://accounts.spotify.com",
    private val api: String = "https://api.spotify.com",
) : SearchProvider {
    override val provider = Provider.SPOTIFY
    private val auth = ClientCredentials("Spotify", "$accounts/api/token", keys)

    override suspend fun search(query: String, limit: Int): List<ProviderHit> {
        val token = auth.bearer()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = parseJson(Http.getText("$api/v1/search?type=track&market=FR&limit=$limit&q=$q", mapOf("Authorization" to "Bearer $token")))
        return json.arr("tracks", "items").mapNotNull { t ->
            ProviderHit(
                provider = provider,
                url = t.str("external_urls", "spotify") ?: return@mapNotNull null,
                title = t.str("name") ?: return@mapNotNull null,
                artist = t.arr("artists").mapNotNull { it.str("name") }.joinToString(", "),
                album = t.str("album", "name"),
                durationSec = t.long("duration_ms")?.let { (it / 1000.0).roundToInt() },
                isrc = t.str("external_ids", "isrc"),
                coverUrl = t.arr("album", "images").maxByOrNull { it.long("width") ?: 0 }.str("url"),
                quality = QUALITY,
            )
        }
    }

    companion object {
        const val QUALITY = "Ogg 320 kb/s / sans perte (selon abonnement)"
    }
}
