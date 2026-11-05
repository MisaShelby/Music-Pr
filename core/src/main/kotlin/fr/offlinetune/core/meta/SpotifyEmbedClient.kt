package fr.offlinetune.core.meta

import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.model.ExtractionBroken
import fr.offlinetune.core.model.NotFound
import fr.offlinetune.core.model.OfflineTuneException
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.at
import fr.offlinetune.core.net.long
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Reads Spotify track info from the public embed page (no account), enriched with Deezer's catalogue. */
class SpotifyEmbedClient(private val deezer: DeezerClient) {

    suspend fun track(id: String): TrackInfo {
        val embed = try {
            parseEmbed(Http.getText("https://open.spotify.com/embed/track/$id"))
        } catch (e: NotFound) {
            throw e
        } catch (e: OfflineTuneException) {
            null
        }
        if (embed != null) return enrich(embed)
        return fallbackViaOembed(id)
    }

    private suspend fun enrich(sp: TrackInfo): TrackInfo {
        val dz = runCatching { deezer.findBest(sp.title, sp.artist, sp.durationSec) }.getOrNull() ?: return sp
        return sp.copy(
            album = dz.album ?: sp.album,
            albumArtist = dz.albumArtist,
            year = sp.year ?: dz.year,
            trackNumber = dz.trackNumber,
            genre = dz.genre,
            coverUrl = dz.coverUrl ?: sp.coverUrl,
            isrc = dz.isrc,
        )
    }

    /** Embed page format changed: oEmbed only gives the title, so look it up on Deezer. */
    private suspend fun fallbackViaOembed(id: String): TrackInfo {
        val url = URLEncoder.encode("https://open.spotify.com/track/$id", "UTF-8")
        val title = runCatching { parseJson(Http.getText("https://open.spotify.com/oembed?url=$url")).str("title") }
            .getOrNull() ?: throw ExtractionBroken(Platform.SPOTIFY)
        val hit = deezer.search(title, 10).firstOrNull { Matcher.normalize(it.title) == Matcher.normalize(title) }
            ?: throw ExtractionBroken(Platform.SPOTIFY)
        return deezer.track(hit.url.substringAfterLast('/'))
    }

    companion object {
        private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

        fun parseEmbed(html: String): TrackInfo? {
            val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
            val e = runCatching { parseJson(json) }.getOrNull().at("props", "pageProps", "state", "data", "entity") ?: return null
            val name = e.str("name") ?: e.str("title") ?: return null
            val artists = e.arr("artists").mapNotNull { it.str("name") }
            val cover = e.arr("visualIdentity", "image").maxByOrNull { it.long("maxWidth") ?: 0 }.str("url")
                ?: e.arr("coverArt", "sources").maxByOrNull { it.long("width") ?: 0 }.str("url")
            return TrackInfo(
                title = name,
                artist = artists.joinToString(", "),
                year = e.str("releaseDate", "isoString")?.take(4)?.toIntOrNull(),
                durationSec = e.long("duration")?.let { (it / 1000.0).roundToInt() },
                coverUrl = cover,
            )
        }
    }
}
