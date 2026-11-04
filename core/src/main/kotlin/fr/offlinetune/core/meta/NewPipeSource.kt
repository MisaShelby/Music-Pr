package fr.offlinetune.core.meta

import fr.offlinetune.core.model.AudioFormat
import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.ExtractionBroken
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.model.NotFound
import fr.offlinetune.core.model.OfflineTuneException
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.SearchHit
import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.net.ExpiringCache
import fr.offlinetune.core.net.NewPipeInit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException

data class ResolvedStream(
    val url: String,
    val format: AudioFormat,
    val durationSec: Int?,
    val isHls: Boolean,
    val contentLength: Long?,
)

/** YouTube / YouTube Music / SoundCloud through NewPipeExtractor. */
class NewPipeSource {

    fun platformOf(url: String): Platform = when {
        "music.youtube.com" in url -> Platform.YOUTUBE_MUSIC
        "soundcloud.com" in url -> Platform.SOUNDCLOUD
        else -> Platform.YOUTUBE
    }

    private suspend fun <T> extract(url: String, block: () -> T): T = withContext(Dispatchers.IO) {
        NewPipeInit.ensure()
        try {
            block()
        } catch (e: OfflineTuneException) {
            throw e
        } catch (e: ContentNotAvailableException) {
            throw NotFound("ce contenu (indisponible ou privé)")
        } catch (e: ExtractionException) {
            throw ExtractionBroken(platformOf(url), e)
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
    }

    /** Stream URLs expire after ~6 h: keep extraction results 30 min at most. */
    private val streamInfoCache = ExpiringCache<String, StreamInfo>(ttlMs = 30 * 60_000L, maxSize = 8)

    private fun streamInfo(url: String): StreamInfo =
        streamInfoCache[url] ?: StreamInfo.getInfo(url).also { streamInfoCache.put(url, it) }

    /** Forget a cached extraction (called after a failed download so a retry re-extracts fresh URLs). */
    fun invalidate(url: String) = streamInfoCache.invalidate(url)

    suspend fun info(url: String): Pair<TrackInfo, Platform> = extract(url) {
        val si = streamInfo(url)
        val year = runCatching { si.uploadDate?.offsetDateTime()?.year }.getOrNull()
        TrackInfo(
            title = si.name,
            artist = cleanUploader(si.uploaderName ?: ""),
            year = year,
            durationSec = si.duration.takeIf { it > 0 }?.toInt(),
            coverUrl = bestImage(si.thumbnails),
        ) to platformOf(url)
    }

    suspend fun bestStream(url: String): ResolvedStream = extract(url) {
        val si = streamInfo(url)
        val streams = si.audioStreams.filter { it.isUrl }
        val dur = si.duration.takeIf { it > 0 }?.toInt()
        fun pick(format: MediaFormat, method: DeliveryMethod) =
            streams.filter { it.format == format && it.deliveryMethod == method }.maxByOrNull { it.averageBitrate }

        val opus = pick(MediaFormat.WEBMA_OPUS, DeliveryMethod.PROGRESSIVE_HTTP)
        if (opus != null) return@extract resolved(opus, Container.OGG_OPUS, "Opus", 160, dur)
        // Otherwise: highest bitrate among AAC (M4A) and MP3, progressive preferred on ties.
        val other = streams
            .filter { it.format in setOf(MediaFormat.M4A, MediaFormat.MP3) }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.HLS }
            .maxWithOrNull(compareBy<AudioStream> { it.averageBitrate }.thenBy { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP })
            ?: throw Unsupported("le format audio proposé par ${platformOf(url).label}")
        return@extract if (other.format == MediaFormat.MP3) resolved(other, Container.MP3, "MP3", 128, dur)
        else resolved(other, Container.M4A, "AAC", 128, dur)
    }

    private fun resolved(s: AudioStream, c: Container, codec: String, defaultKbps: Int, dur: Int?) = ResolvedStream(
        url = s.content,
        format = AudioFormat(c, codec, s.averageBitrate.takeIf { it > 0 } ?: s.bitrate.takeIf { it > 0 }?.let { it / 1000 } ?: defaultKbps),
        durationSec = dur,
        isHls = s.deliveryMethod == DeliveryMethod.HLS,
        contentLength = s.itagItem?.contentLength?.takeIf { it > 0 },
    )

    /**
     * YouTube Music "songs" search. YouTube Music is not available in every country (it returns
     * "no results" there), so an empty answer falls back to a regular YouTube video search.
     */
    suspend fun searchMusicSongs(query: String, limit: Int = 10): List<SourceCandidate> = extract("music.youtube.com") {
        fun search(filter: String): List<SourceCandidate> {
            val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
            ex.fetchPage()
            return ex.initialPage.items.filterIsInstance<StreamInfoItem>().take(limit).map {
                SourceCandidate(
                    url = it.url,
                    title = it.name,
                    artist = cleanUploader(it.uploaderName ?: ""),
                    durationSec = it.duration.takeIf { d -> d > 0 }?.toInt(),
                )
            }
        }
        search(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS).ifEmpty { search(YoutubeSearchQueryHandlerFactory.VIDEOS) }
    }

    /** Free search for the "YouTube" results tab: YouTube Music songs first, then regular videos. */
    suspend fun searchYoutube(query: String, limit: Int = 20): List<SearchHit> = extract("youtube.com") {
        fun search(filter: String, kind: Platform): List<SearchHit> {
            val ex = ServiceList.YouTube.getSearchExtractor(query, listOf(filter), "")
            ex.fetchPage()
            return ex.initialPage.items.filterIsInstance<StreamInfoItem>().map {
                SearchHit(kind, it.url, it.name, cleanUploader(it.uploaderName ?: ""), it.duration.takeIf { d -> d > 0 }?.toInt(), bestImage(it.thumbnails))
            }
        }
        val songs = runCatching { search(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, Platform.YOUTUBE_MUSIC) }.getOrDefault(emptyList())
        val videos = search(YoutubeSearchQueryHandlerFactory.VIDEOS, Platform.YOUTUBE)
        (songs.take(limit / 2) + videos).distinctBy { it.url.substringAfter("v=").take(11) }.take(limit)
    }

    companion object {
        fun cleanUploader(name: String) = name.replace(Regex("""\s*-\s*Topic$"""), "").trim()

        fun bestImage(images: List<Image>?): String? =
            images?.maxByOrNull { maxOf(it.height, it.width) }?.url ?: images?.lastOrNull()?.url
    }
}
