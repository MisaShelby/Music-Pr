package fr.offlinetune.core.sheet

import fr.offlinetune.core.download.Hls
import fr.offlinetune.core.link.LinkParser
import fr.offlinetune.core.link.ParsedInput
import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.lyrics.LyricsProvider
import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.meta.DeezerClient
import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.meta.SpotifyEmbedClient
import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.SearchHit
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

sealed interface InputResult {
    data class Ready(val sheet: Sheet) : InputResult
    data class Results(val deezer: List<SearchHit>, val youtube: List<SearchHit>) : InputResult
}

/** Turns a pasted link / search text into a reviewable [Sheet] (metadata + chosen audio source + lyrics). */
class SheetService(
    private val deezer: DeezerClient,
    private val spotify: SpotifyEmbedClient,
    private val newPipe: NewPipeSource,
    private val lyrics: LyricsProvider,
) {
    suspend fun open(text: String): InputResult = when (val p = LinkParser.parseResolving(text)) {
        is ParsedInput.Query -> coroutineScope {
            val dz = async { runCatching { deezer.search(p.text) }.getOrDefault(emptyList()) }
            val yt = async { runCatching { newPipe.searchYoutube(p.text) }.getOrDefault(emptyList()) }
            InputResult.Results(dz.await(), yt.await())
        }
        is ParsedInput.Link -> InputResult.Ready(
            when (p.platform) {
                Platform.SPOTIFY -> catalogSheet(spotify.track(p.id), Platform.SPOTIFY)
                Platform.DEEZER -> catalogSheet(deezer.track(p.id), Platform.DEEZER)
                Platform.YOUTUBE, Platform.YOUTUBE_MUSIC -> youtubeSheet(p.url)
                Platform.SOUNDCLOUD -> soundcloudSheet(p.url)
            },
        )
        is ParsedInput.ShortLink -> throw Unsupported("ce lien court")
    }

    suspend fun openHit(hit: SearchHit): Sheet = when (hit.kind) {
        Platform.DEEZER -> catalogSheet(deezer.track(hit.url.substringAfterLast('/')), Platform.DEEZER)
        Platform.SOUNDCLOUD -> soundcloudSheet(hit.url)
        else -> youtubeSheet(hit.url)
    }

    /** The user picks one of the candidates or pastes their own YouTube / SoundCloud link. */
    suspend fun withSource(sheet: Sheet, sourceUrl: String): Sheet {
        val link = LinkParser.parseResolving(sourceUrl) as? ParsedInput.Link
            ?: throw Unsupported("cette source (collez un lien YouTube ou SoundCloud)")
        if (link.platform == Platform.SPOTIFY || link.platform == Platform.DEEZER) {
            throw Unsupported("cette source (collez un lien YouTube ou SoundCloud)")
        }
        val (info, _) = newPipe.info(link.url)
        val stream = newPipe.bestStream(link.url)
        val warnings = sheet.warnings.filterNot { it.startsWith(NO_MATCH) || it.startsWith(PREVIEW) }.toMutableList()
        if (link.platform == Platform.SOUNDCLOUD && isPreview(link.url, info.durationSec)) warnings += PREVIEW_ONLY
        val candidate = sheet.candidates.firstOrNull { it.url == link.url }?.copy(rejectedReason = null)
            ?: SourceCandidate(link.url, info.title, info.artist, info.durationSec, 1.0)
        return sheet.copy(source = candidate, format = stream.format, warnings = warnings)
    }

    /** "Rechercher à nouveau" with the (possibly edited) title / artist. */
    suspend fun refreshLyrics(sheet: Sheet, info: TrackInfo): Sheet = sheet.copy(lyrics = findLyrics(info))

    // --- builders ---------------------------------------------------------

    private suspend fun catalogSheet(info: TrackInfo, origin: Platform): Sheet = coroutineScope {
        val lyr = async { findLyrics(info) }
        val cover = async { downloadCover(info.coverUrl) }
        val (source, candidates, warnings) = matchOnYoutube(info)
        val format = source?.let { runCatching { newPipe.bestStream(it.url).format }.getOrNull() }
        Sheet(info, origin, source, candidates, format, warnings, lyr.await(), cover.await())
    }

    private suspend fun matchOnYoutube(info: TrackInfo): Triple<SourceCandidate?, List<SourceCandidate>, List<String>> {
        val query = "${Matcher.firstArtistOf(info.artist)} ${info.title}"
        val ranked = Matcher.rank(info, newPipe.searchMusicSongs(query)).take(5)
        val source = ranked.firstOrNull { it.rejectedReason == null }
        val warnings = if (source == null) listOf(NO_MATCH_FULL) else emptyList()
        return Triple(source, ranked, warnings)
    }

    private suspend fun youtubeSheet(url: String): Sheet = coroutineScope {
        val (raw, platform) = newPipe.info(url)
        var info = splitArtistTitle(raw)
        val dz = runCatching { deezer.findBest(info.title, info.artist, info.durationSec) }.getOrNull()
        if (dz != null) info = dz.copy(durationSec = raw.durationSec)
        val streamDeferred = async { newPipe.bestStream(url) }
        val lyr = async { findLyrics(info) }
        val cover = async { downloadCover(info.coverUrl) }
        val source = SourceCandidate(url, raw.title, raw.artist, raw.durationSec, 1.0)
        Sheet(info, platform, source, listOf(source), streamDeferred.await().format, emptyList(), lyr.await(), cover.await())
    }

    private suspend fun soundcloudSheet(url: String): Sheet = coroutineScope {
        val (info, _) = newPipe.info(url)
        val stream = newPipe.bestStream(url)
        val lyr = async { findLyrics(info) }
        val cover = async { downloadCover(info.coverUrl?.replace("-large.", "-t500x500.")) }
        val scSource = SourceCandidate(url, info.title, info.artist, info.durationSec, 1.0)
        if (!isPreview(url, info.durationSec)) {
            return@coroutineScope Sheet(info, Platform.SOUNDCLOUD, scSource, listOf(scSource), stream.format, emptyList(), lyr.await(), cover.await())
        }
        // Go+ preview: never save an extract silently, propose the YouTube match instead.
        val (yt, candidates, warnings) = matchOnYoutube(info)
        val format = yt?.let { runCatching { newPipe.bestStream(it.url).format }.getOrNull() }
        Sheet(
            info, Platform.SOUNDCLOUD, yt, candidates, format,
            listOf(PREVIEW_ONLY) + warnings, lyr.await(), cover.await(),
        )
    }

    private suspend fun isPreview(url: String, announcedSec: Int?): Boolean {
        if (announcedSec == null) return false
        val s = newPipe.bestStream(url)
        val streamSec = if (s.isHls) Hls.parse(Http.getText(s.url), s.url).totalSec else return false
        return Hls.isPreview(streamSec, announcedSec)
    }

    private suspend fun findLyrics(info: TrackInfo): LyricsResult? = runCatching { lyrics.find(info) }.getOrNull()

    private suspend fun downloadCover(url: String?): ByteArray? = url?.let { runCatching { Http.getBytes(it) }.getOrNull() }

    companion object {
        private const val NO_MATCH = "Aucune correspondance"
        const val NO_MATCH_FULL = "$NO_MATCH à moins de 5 s : choisissez une source ou collez un lien YouTube/SoundCloud"
        private const val PREVIEW = "Extrait SoundCloud"
        const val PREVIEW_ONLY = "$PREVIEW (~30 s) détecté : la correspondance YouTube est proposée à la place"

        fun create(lyrics: LyricsProvider = LrclibClient()): SheetService {
            val deezer = DeezerClient()
            return SheetService(deezer, SpotifyEmbedClient(deezer), NewPipeSource(), lyrics)
        }

        /** YouTube uploads are often titled "Artist - Title (Official Video)". */
        fun splitArtistTitle(info: TrackInfo): TrackInfo {
            val parts = info.title.split(" - ", " – ", limit = 2)
            return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                info.copy(artist = parts[0].trim(), title = Matcher.cleanTitle(parts[1]))
            } else {
                info.copy(title = Matcher.cleanTitle(info.title))
            }
        }
    }
}
