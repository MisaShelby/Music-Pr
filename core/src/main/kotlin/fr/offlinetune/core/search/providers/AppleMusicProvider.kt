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

/** Apple Music catalogue through the public iTunes Search API (no key). Metadata only. */
class AppleMusicProvider(private val base: String = "https://itunes.apple.com") : SearchProvider {
    override val provider = Provider.APPLE_MUSIC

    override suspend fun search(query: String, limit: Int): List<ProviderHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = parseJson(Http.getText("$base/search?term=$q&entity=song&country=FR&limit=$limit"))
        return json.arr("results").mapNotNull { r ->
            ProviderHit(
                provider = provider,
                url = r.str("trackViewUrl") ?: return@mapNotNull null,
                title = r.str("trackName") ?: return@mapNotNull null,
                artist = r.str("artistName") ?: "",
                album = r.str("collectionName"),
                durationSec = r.long("trackTimeMillis")?.let { (it / 1000.0).roundToInt() },
                isrc = null,
                coverUrl = r.str("artworkUrl100")?.replace("100x100bb", "1000x1000bb"),
                quality = QUALITY,
            )
        }
    }

    companion object {
        const val QUALITY = "ALAC sans perte (selon abonnement)"
    }
}
