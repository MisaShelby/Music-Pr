package fr.offlinetune.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
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
 *
 * Crossfade: the aux player loads the outgoing item paused (Preparing); once it is READY and positioned where
 * main is, aux starts, main jumps to the next item muted, and both gains follow an equal-power curve (Fading).
 * Any pause of main — user, call, headphones unplugged, error — stops the aux player at once.
 */
@UnstableApi
class CrossfadeController(private val context: Context) {
    @Volatile var settings: AppSettings = AppSettings()

    @Volatile private var mainBassCut = 0f
    @Volatile private var auxBassCut = 0f
    val main: ExoPlayer = buildPlayer(context, handleFocus = true, tap = true) { mainBassCut }
    private val handler = Handler(Looper.getMainLooper())

    private sealed interface Xf {
        data object Idle : Xf
        data class Preparing(val aux: ExoPlayer, val next: Boolean, val manual: Boolean, var seeked: Boolean = false) : Xf
        data class Fading(val aux: ExoPlayer, val start: Long, val durationMs: Long) : Xf
    }
    private var xf: Xf = Xf.Idle

    // Volume = pause/resume fade × crossfade gain.
    private var fadeGain = 1f
    private var xfadeGain = 1f
    private var auxOutGain = 1f
    private var fadeAnim: Runnable? = null

    private fun applyVolume() {
        main.volume = fadeGain * xfadeGain
        (xf as? Xf.Fading)?.aux?.volume = fadeGain * auxOutGain
    }

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
        main.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Covers audio focus loss (calls), headphones unplugged, end of queue…
                if (!playWhenReady) cancelCrossfade()
            }
            override fun onPlayerError(error: PlaybackException) = cancelCrossfade()
        })
        handler.post(tick)
    }

    private fun step() {
        when (val s = xf) {
            is Xf.Fading -> {
                val t = (SystemClock.elapsedRealtime() - s.start).toFloat() / s.durationMs
                val (gIn, gOut) = Fades.crossfadeGains(t)
                xfadeGain = gIn; auxOutGain = gOut
                applyVolume()
                djPlan?.let { dj ->
                    // Bass swap over ~2 beats around the middle: incoming bass comes in as the outgoing bass goes out.
                    val w = ((dj.beatMs ?: 500.0) * 2 / s.durationMs).toFloat().coerceIn(0.05f, 0.4f)
                    val x = ((t - (0.5f - w / 2)) / w).coerceIn(0f, 1f)
                    mainBassCut = BASS_CUT * (1 - x); auxBassCut = BASS_CUT * x
                    // Ease the incoming tempo back to normal over the last quarter.
                    if (dj.inSpeed != 1.0 && t > 0.75f) {
                        val k = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
                        main.playbackParameters = androidx.media3.common.PlaybackParameters((userSpeed * (dj.inSpeed + (1 - dj.inSpeed) * k)).toFloat(), 1f)
                    }
                }
                if (t >= 1f) cancelCrossfade()
            }
            is Xf.Preparing -> {
                // Automatic transition reached its end before aux was ready: give up, main plays on gaplessly.
                if (!s.manual && !main.isPlaying) cancelCrossfade()
            }
            Xf.Idle -> {
                val st = settings
                if (!main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) return
                if (st.autoMix) {
                    val plan = autoPlan()
                    if (plan != null && main.currentPosition >= plan.startAtMs - Fades.PREPARE_LEAD_MS && main.currentPosition < plan.startAtMs + plan.fadeMs) {
                        prepare(next = true, manual = false)
                    }
                    return
                }
                if (!st.crossfadeEnabled || st.crossfadeSec == 0) return
                if (Fades.shouldPrepareCrossfade(main.duration, main.currentPosition, st.crossfadeSec * 1000L)) {
                    prepare(next = true, manual = false)
                }
            }
        }
    }

    private fun crossfadeSkip(next: Boolean): Boolean {
        val s = settings
        if (!s.crossfadeEnabled || !s.crossfadeOnSkip || s.crossfadeSec == 0 || !main.isPlaying || xf != Xf.Idle) return false
        if (next && !main.hasNextMediaItem()) return false
        if (!next && !main.hasPreviousMediaItem()) return false
        prepare(next, manual = true)
        return true
    }

    /** Loads the outgoing item on a fresh aux player, paused, at main's position. */
    private fun prepare(next: Boolean, manual: Boolean) {
        val item = main.currentMediaItem ?: return
        val a = buildPlayer(context, handleFocus = false) { auxBassCut }
        val p = Xf.Preparing(a, next, manual)
        xf = p
        a.setMediaItem(item, main.currentPosition)
        a.playWhenReady = false
        a.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (xf !== p || state != Player.STATE_READY) return
                if (!p.seeked) {
                    // Main kept playing while aux loaded: catch up to where main is now, then go.
                    p.seeked = true
                    a.seekTo(main.currentPosition + 30)
                } else {
                    go(p)
                }
            }
            override fun onPlayerError(error: PlaybackException) { if (xf === p) cancelCrossfade() }
        })
        a.prepare()
    }

    // --- Mix automatique: analyse the end of the current track and the start of the next one ---
    private var planKey: String? = null
    @Volatile private var plan: fr.offlinetune.core.playback.DjPlan? = null
    private val analysis = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var djPlan: fr.offlinetune.core.playback.DjPlan? = null
    private var userSpeed = 1f

    /** Plan for (current, next), computed in the background once the current track's duration is known. */
    private fun autoPlan(): fr.offlinetune.core.playback.DjPlan? {
        val cur = main.currentMediaItem ?: return null
        val nextIndex = main.nextMediaItemIndex.takeIf { it >= 0 } ?: return null
        val next = main.getMediaItemAt(nextIndex)
        val dur = main.duration.takeIf { it > 0 } ?: return null
        val key = cur.mediaId + "|" + next.mediaId + "|" + dur
        if (key != planKey) {
            planKey = key; plan = null
            val manual = settings.crossfadeSec.coerceAtLeast(2) * 1000L
            val tailStart = (dur - 40_000).coerceAtLeast(0)
            analysis.execute {
                // Loudness (100 ms) for the outro/silences, fine envelope (10 ms) for the tempo and the beats.
                val out = AudioAnalyzer.envelope(context, cur.mediaId, tailStart, dur) ?: FloatArray(0)
                val inn = AudioAnalyzer.envelope(context, next.mediaId, 0, 15_000) ?: FloatArray(0)
                val outFine = AudioAnalyzer.envelope(context, cur.mediaId, tailStart, dur, 10)
                val inFine = AudioAnalyzer.envelope(context, next.mediaId, 0, 20_000, 10)
                val outBeats = outFine?.let { fr.offlinetune.core.playback.BeatAnalyzer.analyze(it) }?.let { it.copy(firstBeatMs = it.firstBeatMs + tailStart) }
                val inBeats = inFine?.let { fr.offlinetune.core.playback.BeatAnalyzer.analyze(it) }
                val p = fr.offlinetune.core.playback.DjMix.plan(outBeats, inBeats, out, tailStart, dur, inn, manual)
                handler.post { if (planKey == key) plan = p }
            }
        }
        return plan
    }

    private fun go(p: Xf.Preparing) {
        val auto = if (!p.manual && settings.autoMix) plan else null
        val fadeMs = when {
            p.manual -> settings.crossfadeSec * 1000L
            auto != null -> minOf(auto.fadeMs, main.duration - main.currentPosition)
            else -> main.duration - main.currentPosition
        }
        if (fadeMs < 300) { cancelCrossfade(); return }
        auxOutGain = 1f
        xf = Xf.Fading(p.aux, SystemClock.elapsedRealtime(), fadeMs)
        p.aux.volume = fadeGain
        p.aux.play()
        xfadeGain = 0f
        applyVolume()
        if (p.next) {
            // Mix automatique: the next track starts after its leading silence.
            if (auto != null && main.nextMediaItemIndex >= 0) {
                djPlan = auto
                userSpeed = main.playbackParameters.speed
                main.seekTo(main.nextMediaItemIndex, auto.inStartMs)
                // Incoming track at the outgoing tempo during the transition (pitch kept).
                if (auto.inSpeed != 1.0) main.playbackParameters = androidx.media3.common.PlaybackParameters((auto.inSpeed * userSpeed).toFloat(), 1f)
                mainBassCut = BASS_CUT; auxBassCut = 0f
            } else main.seekToNextMediaItem()
        } else main.seekToPreviousMediaItem()
    }

    private fun cancelCrossfade() {
        when (val s = xf) {
            is Xf.Preparing -> s.aux.release()
            is Xf.Fading -> s.aux.release()
            Xf.Idle -> {}
        }
        xf = Xf.Idle
        if (djPlan?.inSpeed?.let { it != 1.0 } == true) main.playbackParameters = androidx.media3.common.PlaybackParameters(userSpeed, 1f)
        djPlan = null
        mainBassCut = 0f; auxBassCut = 0f
        xfadeGain = 1f
        auxOutGain = 1f
        applyVolume()
    }

    private fun playWithFade() {
        cancelFade()
        if (!settings.pauseFade) { fadeGain = 1f; applyVolume(); main.play(); return }
        fadeGain = 0f; applyVolume()
        main.play()
        animateFade(to = 1f) {}
    }

    /** Fades both players down together, then pauses (the crossfade is dropped by main's listener). */
    private fun pauseWithFade() {
        cancelFade()
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
        analysis.shutdownNow()
        cancelCrossfade()
        main.release()
    }

    companion object {
        private const val PAUSE_FADE_MS = 250L
        private const val BASS_CUT = -24f

        fun buildPlayer(context: Context, handleFocus: Boolean, tap: Boolean = false, bassCut: () -> Float = { 0f }): ExoPlayer {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(DspAudioProcessor(bassCut, tap))).build()
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
