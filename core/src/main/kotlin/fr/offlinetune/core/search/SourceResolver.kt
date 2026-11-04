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
)

data class SongSources(val downloadable: List<DownloadOption>, val listenOnly: List<ProviderHit>, val message: String?)

/** What the user sees after touching a song: downloadable sources first, then the platforms to listen on. */
class SourceResolver(private val newPipe: NewPipeSource, private val lyrics: LyricsProvider) {

    suspend fun resolve(song: SongResult): SongSources = coroutineScope {
        val hasLyrics = async {
            runCatching { lyrics.find(TrackInfo(song.title, song.artist, song.album, durationSec = song.durationSec)) != null }.getOrDefault(false)
        }
        val direct = song.hits.values.filter { it.provider.downloadable }
        var options = direct.mapNotNull { h ->
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
                options = listOf(DownloadOption(Provider.YOUTUBE_MUSIC, match.url, match.title, match.artist, match.durationSec, format, isMatch = true, hasLyrics = false))
            }
        }
        val l = hasLyrics.await()
        SongSources(
            downloadable = options.map { it.copy(hasLyrics = l) },
            listenOnly = song.hits.values.filter { !it.provider.downloadable },
            message = if (options.isEmpty()) NO_DOWNLOAD else null,
        )
    }

    companion object {
        const val NO_DOWNLOAD = "Aucune version téléchargeable trouvée — collez un lien YouTube ou SoundCloud"

        fun create(newPipe: NewPipeSource = NewPipeSource(), lyrics: LyricsProvider = LrclibClient()) = SourceResolver(newPipe, lyrics)
    }
}
