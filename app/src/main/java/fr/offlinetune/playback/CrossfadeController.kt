package fr.offlinetune.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import fr.offlinetune.settings.AppSettings

/**
 * Owns the main ExoPlayer and an auxiliary one used only during crossfades.
 * [sessionPlayer] is what the MediaSession exposes: it adds pause/resume fades and crossfade on manual skip.
 */
@UnstableApi
class CrossfadeController(private val context: Context) {
    @Volatile var settings: AppSettings = AppSettings()

    val main: ExoPlayer = buildPlayer(context, handleFocus = true)
    private var aux: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Volume = pause/resume fade × crossfade gain.
    private var fadeGain = 1f
    private var xfadeGain = 1f
    private fun applyVolume() {
        main.volume = fadeGain * xfadeGain
    }

    private var xfadeStart = 0L
    private var xfadeDurationMs = 0L
    private var auxOutGain = 1f
    private var fadeAnim: Runnable? = null

    val sessionPlayer: Player = object : ForwardingPlayer(main) {
        override fun play() = playWithFade()
        override fun pause() = pauseWithFade()
        override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) playWithFade() else pauseWithFade()
        override fun seekToNext() { if (!crossfadeSkip(next = true)) super.seekToNext() }
        override fun seekToNextMediaItem() { if (!crossfadeSkip(next = true)) super.seekToNextMediaItem() }
        override fun seekToPrevious() {
            if (main.currentPosition > 3000 || !crossfadeSkip(next = false)) super.seekToPrevious()
        }
        override fun seekToPreviousMediaItem() { if (!crossfadeSkip(next = false)) super.seekToPreviousMediaItem() }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 50)
        }
    }

    init {
        handler.post(tick)
    }

    private fun step() {
        val a = aux
        if (a != null) {
            val t = (SystemClock.elapsedRealtime() - xfadeStart).toFloat() / xfadeDurationMs
            val (gIn, gOut) = Fades.crossfadeGains(t)
            xfadeGain = gIn; auxOutGain = gOut
            applyVolume()
            a.volume = fadeGain * auxOutGain
            if (t >= 1f) finishCrossfade()
            return
        }
        val s = settings
        if (!s.crossfadeEnabled || !main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
        if (Fades.shouldStartCrossfade(main.duration, main.currentPosition, s.crossfadeSec * 1000L)) {
            startCrossfade(next = true, durationMs = main.duration - main.currentPosition)
        }
    }

    private fun crossfadeSkip(next: Boolean): Boolean {
        val s = settings
        if (!s.crossfadeEnabled || !s.crossfadeOnSkip || s.crossfadeSec == 0 || !main.isPlaying || aux != null) return false
        if (next && !main.hasNextMediaItem()) return false
        if (!next && !main.hasPreviousMediaItem()) return false
        startCrossfade(next, s.crossfadeSec * 1000L)
        return true
    }

    /** The outgoing track keeps playing on [aux] while [main] moves on and fades in. */
    private fun startCrossfade(next: Boolean, durationMs: Long) {
        val item = main.currentMediaItem ?: return
        val position = main.currentPosition
        val a = buildPlayer(context, handleFocus = false)
        a.setMediaItem(item, position)
        a.volume = fadeGain
        a.prepare()
        a.play()
        aux = a
        auxOutGain = 1f
        xfadeGain = 0f
        applyVolume()
        if (next) main.seekToNextMediaItem() else main.seekToPreviousMediaItem()
        xfadeStart = SystemClock.elapsedRealtime()
        xfadeDurationMs = durationMs.coerceAtLeast(500)
    }

    private fun finishCrossfade() {
        aux?.release()
        aux = null
        xfadeGain = 1f
        applyVolume()
    }

    private fun playWithFade() {
        cancelFade()
        if (!settings.pauseFade) { fadeGain = 1f; applyVolume(); main.play(); return }
        fadeGain = 0f; applyVolume()
        main.play()
        animateFade(to = 1f) {}
    }

    private fun pauseWithFade() {
        cancelFade()
        if (aux != null) finishCrossfade()
        if (!settings.pauseFade || !main.isPlaying) { main.pause(); fadeGain = 1f; applyVolume(); return }
        animateFade(to = 0f) { main.pause(); fadeGain = 1f; applyVolume() }
    }

    private fun animateFade(to: Float, done: () -> Unit) {
        val from = fadeGain
        val start = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                val t = ((SystemClock.elapsedRealtime() - start) / PAUSE_FADE_MS.toFloat()).coerceIn(0f, 1f)
                fadeGain = from + (to - from) * t
                applyVolume()
                aux?.volume = fadeGain * auxOutGain
                if (t < 1f) handler.postDelayed(this, 16) else { fadeAnim = null; done() }
            }
        }
        fadeAnim = r
        handler.post(r)
    }

    private fun cancelFade() {
        fadeAnim?.let { handler.removeCallbacks(it) }
        fadeAnim = null
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        aux?.release()
        main.release()
    }

    companion object {
        private const val PAUSE_FADE_MS = 250L

        fun buildPlayer(context: Context, handleFocus: Boolean): ExoPlayer {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(DspAudioProcessor())).build()
            }
            return ExoPlayer.Builder(context, renderers)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                    handleFocus,
                )
                .setHandleAudioBecomingNoisy(handleFocus)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }
    }
}
