package fr.offlinetune.core.search

import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.lyrics.LyricsProvider
import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.model.AudioFormat
import fr.offlinetune.core.model.TrackInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A source the audio can actually be downloaded from (YouTube Music / SoundCloud only), with its real quality. */
data class DownloadOption(
    val provider: Provider,
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val format: AudioFormat,
    /** Found by matching on YouTube because the song was only on paid platforms. */
    val isMatch: Boolean,
    val hasLyrics: Boolean,
    val label: String = provider.label,
    /**
     * For a match: the platform the song was found on (Apple Music, Deezer…), shown instead of the place the audio
     * really comes from. Display only.
     */
    val shownAs: ProviderHit? = null,
)

/** Audio address to listen to before (or instead of) downloading. [sourceUrl] is the page it comes from. */
data class ListenStream(val url: String, val isHls: Boolean, val sourceUrl: String)

data class SongSources(val downloadable: List<DownloadOption>, val listenOnly: List<ProviderHit>, val message: String?)

/** What the user sees after touching a song: downloadable sources first, then the platforms to listen on. */
class SourceResolver(private val newPipe: NewPipeSource, private val lyrics: LyricsProvider) {

    suspend fun resolve(song: SongResult): SongSources = coroutineScope {
        val hasLyrics = async {
            runCatching { lyrics.find(TrackInfo(song.title, song.artist, song.album, durationSec = song.durationSec)) != null }.getOrDefault(false)
        }
        val direct = song.hits.values.filter { it.provider.downloadable && it.provider != Provider.CUSTOM }
        // « Sources perso »: the manifest already gives a direct audio link and its format.
        val custom = song.hits.values.filter { it.provider == Provider.CUSTOM }.mapNotNull { h ->
            fr.offlinetune.core.search.custom.CustomAudio.decode(h.url)?.let { (c, _) ->
                DownloadOption(h.provider, h.url, h.title, h.artist, h.durationSec, fr.offlinetune.core.search.custom.customFormat(c, h.quality), isMatch = false, hasLyrics = false, label = h.label)
            }
        }
        var options = custom + direct.mapNotNull { h ->
            runCatching { newPipe.bestStream(h.url) }.getOrNull()?.let { s ->
                DownloadOption(h.provider, h.url, h.title, h.artist, h.durationSec, s.format, isMatch = false, hasLyrics = false, label = h.label)
            }
        }
        if (options.isEmpty()) {
            val ref = TrackInfo(song.title, song.artist, durationSec = song.durationSec)
            val match = Matcher.rank(ref, runCatching { newPipe.searchMusicSongs("${Matcher.firstArtistOf(song.artist)} ${Matcher.cleanTitle(song.title)}") }.getOrDefault(emptyList()))
                .firstOrNull { it.rejectedReason == null }
            val format = match?.let { runCatching { newPipe.bestStream(it.url).format }.getOrNull() }
            if (match != null && format != null) {
                val base = DownloadOption(Provider.YOUTUBE_MUSIC, match.url, match.title, match.artist, match.durationSec, format, isMatch = true, hasLyrics = false)
                // One line per platform the song was found on, each shown under that platform's name.
                val shown = CATALOG_PRIORITY.mapNotNull { song.hits[it] }.filter { !it.provider.downloadable }
                options = if (shown.isEmpty()) listOf(base) else shown.map { base.copy(shownAs = it) }
            }
        }
        val l = hasLyrics.await()
        SongSources(
            downloadable = options.map { it.copy(hasLyrics = l) },
            listenOnly = song.hits.values.filter { h -> !h.provider.downloadable && options.none { it.shownAs?.provider == h.provider } },
            message = if (options.isEmpty()) NO_DOWNLOAD else null,
        )
    }

    /** Playable address of one source (YouTube / SoundCloud page, or a « Sources perso » direct link). */
    suspend fun streamOf(sourceUrl: String): ListenStream {
        fr.offlinetune.core.search.custom.CustomAudio.decode(sourceUrl)?.let { (_, link) -> return ListenStream(link, isHls = link.contains(".m3u8"), sourceUrl) }
        val s = newPipe.bestStream(sourceUrl)
        return ListenStream(s.url, s.isHls, sourceUrl)
    }

    /**
     * Something to listen to for a search result, without downloading it: its own YouTube / SoundCloud / perso
     * source when it has one, otherwise the same YouTube match a download would use.
     */
    suspend fun listen(song: SongResult): ListenStream {
        val direct = song.hits.values.filter { it.provider.downloadable }
            .sortedBy { listOf(Provider.CUSTOM, Provider.YOUTUBE_MUSIC, Provider.SOUNDCLOUD).indexOf(it.provider) }
        for (h in direct) runCatching { return streamOf(h.url) }
        val ref = TrackInfo(song.title, song.artist, durationSec = song.durationSec)
        val match = Matcher.rank(ref, newPipe.searchMusicSongs("${Matcher.firstArtistOf(song.artist)} ${Matcher.cleanTitle(song.title)}"))
            .firstOrNull { it.rejectedReason == null }
            ?: throw fr.offlinetune.core.model.NotFound("une version à écouter")
        return streamOf(match.url)
    }

    companion object {
        const val NO_DOWNLOAD = "Aucune version téléchargeable trouvée — collez un lien YouTube ou SoundCloud"

        fun create(newPipe: NewPipeSource = NewPipeSource(), lyrics: LyricsProvider = LrclibClient()) = SourceResolver(newPipe, lyrics)
    }
}
