package fr.offlinetune.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.search.ListenStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What is being listened to from the Télécharger page. [key] null = nothing. */
data class PreviewState(
    val key: String? = null,
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * Listening to a song found online, straight from its source, without downloading it — also the « aperçu » of
 * the source chosen for a download. Its own small player: the library's music pauses (audio focus) meanwhile.
 */
@UnstableApi
class PreviewPlayer(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state.asStateFlow()
    private var player: ExoPlayer? = null
    private var job: Job? = null
    private var ticker: Job? = null

    private fun player(): ExoPlayer = player ?: run {
        val http = DefaultHttpDataSource.Factory().setUserAgent(Http.USER_AGENT).setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) = _state.update { it.copy(playing = isPlaying) }
                    override fun onPlaybackStateChanged(state: Int) {
                        _state.update { it.copy(loading = state == Player.STATE_BUFFERING, durationMs = p.duration.coerceAtLeast(0)) }
                        if (state == Player.STATE_ENDED) { p.pause(); p.seekTo(0) }
                    }
                    override fun onPlayerError(error: PlaybackException) =
                        _state.update { it.copy(loading = false, playing = false, error = "Lecture impossible (${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase()})") }
                })
                player = p
            }
    }

    /**
     * Plays [key] (or pauses / resumes it when it is already the one loaded). [resolve] finds the audio address,
     * [startFraction] where to begin (0.3 = a third in, usually past the intro).
     */
    fun toggle(key: String, title: String, artist: String, coverUrl: String?, startFraction: Float = 0f, resolve: suspend () -> ListenStream) {
        val cur = _state.value
        if (cur.key == key && cur.error == null) {
            if (cur.loading && player?.isPlaying != true && job?.isActive == true) return
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        job?.cancel()
        player?.stop()
        _state.value = PreviewState(key, title, artist, coverUrl, loading = true)
        job = scope.launch {
            val stream = try { resolve() } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.update { if (it.key == key) it.copy(loading = false, error = t.userMessage()) else it }
                return@launch
            }
            val p = player()
            val item = MediaItem.Builder().setUri(stream.url).apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
            p.setMediaItem(item)
            p.prepare()
            if (startFraction > 0f) {
                // Jump once the length is known.
                var waited = 0
                while (p.duration == C.TIME_UNSET && waited < 40 && p.playerError == null) { delay(100); waited++ }
                if (p.duration != C.TIME_UNSET) p.seekTo((p.duration * startFraction).toLong())
            }
            p.play()
            startTicker()
        }
    }

    fun seekTo(ms: Long) { player?.seekTo(ms); _state.update { it.copy(positionMs = ms) } }

    fun pause() { player?.pause() }

    fun resume() { player?.play() }

    /** Stops and forgets the preview (leaving the Télécharger page, closing the bar). */
    fun stop() {
        job?.cancel(); ticker?.cancel()
        player?.run { stop(); clearMediaItems() }
        _state.value = PreviewState()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                player?.let { p -> _state.update { it.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0)) } }
                delay(250)
            }
        }
    }
}
