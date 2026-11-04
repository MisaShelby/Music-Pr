package fr.offlinetune.core.search.providers

import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.int
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.SearchProvider
import java.net.URLEncoder

/** Deezer public API (no key). Metadata only. */
class DeezerProvider(private val base: String = "https://api.deezer.com") : SearchProvider {
    override val provider = Provider.DEEZER

    override suspend fun search(query: String, limit: Int): List<ProviderHit> {
        val json = parseJson(Http.getText("$base/search/track?limit=$limit&q=${URLEncoder.encode(query, "UTF-8")}"))
        return json.arr("data").mapNotNull { t ->
            ProviderHit(
                provider = provider,
                url = "https://www.deezer.com/track/${t.str("id") ?: return@mapNotNull null}",
                title = t.str("title") ?: return@mapNotNull null,
                artist = t.str("artist", "name") ?: "",
                album = t.str("album", "title"),
                durationSec = t.int("duration"),
                isrc = t.str("isrc"),
                coverUrl = t.str("album", "cover_xl"),
                quality = QUALITY,
            )
        }
    }

    companion object {
        const val QUALITY = "FLAC 16 bit (selon abonnement)"
    }
}

/**
 * YouTube Music songs (falls back to YouTube videos where YouTube Music is unavailable). Downloadable;
 * the real quality is resolved when the song is opened.
 */
class YouTubeMusicProvider(private val newPipe: NewPipeSource = NewPipeSource()) : SearchProvider {
    override val provider = Provider.YOUTUBE_MUSIC

    override suspend fun search(query: String, limit: Int): List<ProviderHit> =
        newPipe.searchYoutube(query, limit).map {
            val label = if (it.kind == fr.offlinetune.core.model.Platform.YOUTUBE_MUSIC) "YouTube Music" else "YouTube"
            ProviderHit(provider, it.url, it.title, it.artist, null, it.durationSec, null, it.thumbUrl, "", label)
        }
}

/** SoundCloud tracks. Downloadable; the real quality is resolved when the song is opened. */
class SoundCloudProvider(private val newPipe: NewPipeSource = NewPipeSource()) : SearchProvider {
    override val provider = Provider.SOUNDCLOUD

    override suspend fun search(query: String, limit: Int): List<ProviderHit> =
        newPipe.searchSoundCloud(query, limit).map {
            ProviderHit(provider, it.url, it.title, it.artist, null, it.durationSec, null, it.thumbUrl, "")
        }
}
