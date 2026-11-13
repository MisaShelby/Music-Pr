package fr.offlinetune.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import fr.offlinetune.core.playback.DjPlan
import fr.offlinetune.settings.AppSettings

/**
 * Playback with crossfades between two players (« decks »).
 *
 * The current deck plays the queue. For a crossfade, a second deck is loaded with the same queue, positioned on the
 * incoming song, paused. When the fade begins it simply starts playing and becomes the current deck (the
 * MediaSession is given its player, see [onSwap]); the outgoing deck is never touched — it goes on playing its song,
 * fading out, and is released when the fade is over. Nothing is handed over between players in the middle of a song,
 * so there is nothing to align and nothing can be heard twice or skipped.
 *
 * Any pause of the current deck — user, call, headphones unplugged, error — stops the other deck at once.
 */
@UnstableApi
class CrossfadeController(private val context: Context) {
    @Volatile var settings: AppSettings = AppSettings()

    private inner class Deck(handleFocus: Boolean) {
        @Volatile var bassCut = 0f
        /** Feeds the « Spectre » visualizer: only the current deck does. */
        @Volatile var tap = false
        val player: ExoPlayer = buildPlayer(context, handleFocus, { tap }) { bassCut }

        /** What the MediaSession exposes for this deck: adds pause/resume fades and crossfade on manual skip. */
        val session: Player = object : ForwardingPlayer(player) {
            override fun play() = playWithFade()
            override fun pause() = pauseWithFade()
            override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) playWithFade() else pauseWithFade()
            override fun seekToNext() { if (!crossfadeSkip(next = true)) super.seekToNext() }
            override fun seekToNextMediaItem() { if (!crossfadeSkip(next = true)) super.seekToNextMediaItem() }
            override fun seekToPrevious() {
                if (player.currentPosition > 3000 || !crossfadeSkip(next = false)) super.seekToPrevious()
            }
            override fun seekToPreviousMediaItem() { if (!crossfadeSkip(next = false)) super.seekToPreviousMediaItem() }
        }
    }

    private var cur = Deck(handleFocus = true).also { it.tap = true }

    /** The player of the current deck (changes at each crossfade: never keep it, never add listeners to it directly). */
    val main: ExoPlayer get() = cur.player
    val sessionPlayer: Player get() = cur.session

    /** Called when another deck became the current one: the MediaSession must be given this player. */
    var onSwap: (Player) -> Unit = {}

    private val listeners = mutableListOf<Player.Listener>()

    /** Listener that follows the current deck through crossfades. */
    fun addListener(l: Player.Listener) { listeners += l; cur.player.addListener(l) }

    private val handler = Handler(Looper.getMainLooper())

    private sealed interface Xf {
        data object Idle : Xf
        /** [next] is loaded on the incoming song, paused, waiting for the fade to begin. */
        class Preparing(val next: Deck, val manual: Boolean, val fromId: String, val auto: DjPlan?) : Xf
        /** [out] finishes the outgoing song while the current deck plays the incoming one. */
        class Fading(val out: Deck, val start: Long, val durationMs: Long) : Xf
    }
    private var xf: Xf = Xf.Idle

    /** Song for which a crossfade was given up (too short, error): not tried again until the song changes. */
    private var noRetryId: String? = null

    // Volume = pause/resume fade × crossfade gain.
    private var fadeGain = 1f
    private var xfadeGain = 1f
    private var outGain = 1f
    private var fadeAnim: Runnable? = null

    private fun applyVolume() {
        cur.player.volume = fadeGain * xfadeGain
        (xf as? Xf.Fading)?.out?.player?.volume = fadeGain * outGain
    }

    /** On the current deck only. */
    private val curListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Covers audio focus loss (calls), headphones unplugged, end of queue…
            if (!playWhenReady) cancelCrossfade()
        }
        override fun onPlayerError(error: PlaybackException) = cancelCrossfade()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Queue edited while the next deck waits with the old queue: drop it, it is prepared again.
            if (xf is Xf.Preparing) cancelCrossfade()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 50)
        }
    }

    init {
        cur.player.addListener(curListener)
        handler.post(tick)
    }

    private fun step() {
        when (val s = xf) {
            is Xf.Fading -> {
                val t = (SystemClock.elapsedRealtime() - s.start).toFloat() / s.durationMs
                val (gIn, gOut) = Fades.crossfadeGains(t)
                xfadeGain = gIn; outGain = gOut
                applyVolume()
                djPlan?.let { dj ->
                    // Bass swap over ~2 beats around the middle: incoming bass comes in as the outgoing bass goes out.
                    val w = ((dj.beatMs ?: 500.0) * 2 / s.durationMs).toFloat().coerceIn(0.05f, 0.4f)
                    val x = ((t - (0.5f - w / 2)) / w).coerceIn(0f, 1f)
                    cur.bassCut = BASS_CUT * (1 - x); s.out.bassCut = BASS_CUT * x
                    // Ease the incoming tempo back to normal over the last quarter.
                    if (dj.inSpeed != 1.0 && t > 0.75f) {
                        val k = ((t - 0.75f) / 0.25f).coerceIn(0f, 1f)
                        val target = (userSpeed * (dj.inSpeed + (1 - dj.inSpeed) * k)).toFloat()
                        val now = cur.player.playbackParameters.speed
                        if (kotlin.math.abs(target - now) >= 0.004f || k >= 1f && target != now) {
                            cur.player.playbackParameters = PlaybackParameters(target, 1f)
                        }
                    }
                }
                if (t >= 1f) cancelCrossfade()
            }
            is Xf.Preparing -> {
                val a = cur.player
                // The song changed meanwhile (skip, or it ended before the next deck was ready): give up.
                if (a.currentMediaItem?.mediaId != s.fromId) { cancelCrossfade(); return }
                if (!a.isPlaying && a.playbackState != Player.STATE_BUFFERING) { cancelCrossfade(); return }
                if (s.next.player.playbackState != Player.STATE_READY) return
                val remaining = a.duration - a.currentPosition
                when {
                    s.manual -> start(s, settings.crossfadeSec * 1000L)
                    s.auto != null -> if (a.currentPosition >= s.auto.startAtMs) start(s, minOf(s.auto.fadeMs, remaining))
                    else -> if (remaining <= settings.crossfadeSec * 1000L) start(s, remaining)
                }
            }
            Xf.Idle -> {
                val st = settings
                val a = cur.player
                val id = a.currentMediaItem?.mediaId
                if (id != noRetryId) noRetryId = null
                if (!a.isPlaying || a.repeatMode == Player.REPEAT_MODE_ONE || !a.hasNextMediaItem() || id == null || noRetryId != null) return
                if (st.autoMix) {
                    val plan = autoPlan()
                    if (plan != null && a.currentPosition >= plan.startAtMs - Fades.PREPARE_LEAD_MS && a.currentPosition < plan.startAtMs + plan.fadeMs) {
                        prepare(a.nextMediaItemIndex, manual = false, startPos = plan.inStartMs, auto = plan)
                    }
                    return
                }
                if (!st.crossfadeEnabled || st.crossfadeSec == 0) return
                if (Fades.shouldPrepareCrossfade(a.duration, a.currentPosition, st.crossfadeSec * 1000L)) {
                    prepare(a.nextMediaItemIndex, manual = false, startPos = 0, auto = null)
                }
            }
        }
    }

    private fun crossfadeSkip(next: Boolean): Boolean {
        val s = settings
        val a = cur.player
        if (!s.crossfadeEnabled || !s.crossfadeOnSkip || s.crossfadeSec == 0 || !a.isPlaying || xf != Xf.Idle) return false
        if (next && !a.hasNextMediaItem()) return false
        if (!next && !a.hasPreviousMediaItem()) return false
        prepare(if (next) a.nextMediaItemIndex else a.previousMediaItemIndex, manual = true, startPos = 0, auto = null)
        return xf is Xf.Preparing
    }

    /** Loads a fresh deck with the same queue, on song [target], paused and silent. */
    private fun prepare(target: Int, manual: Boolean, startPos: Long, auto: DjPlan?) {
        val a = cur.player
        val id = a.currentMediaItem?.mediaId ?: return
        if (target < 0 || target >= a.mediaItemCount) return
        val d = Deck(handleFocus = false)
        val p = Xf.Preparing(d, manual, id, auto)
        d.player.volume = 0f
        d.player.setMediaItems(List(a.mediaItemCount) { a.getMediaItemAt(it) }, target, startPos)
        d.player.playWhenReady = false
        d.player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { if (xf === p) { noRetryId = id; cancelCrossfade() } }
        })
        xf = p
        d.player.prepare()
    }

    // --- Mix automatique: analyse the end of the current track and the start of the next one ---
    private var planKey: String? = null
    @Volatile private var plan: DjPlan? = null
    private val analysis = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var djPlan: DjPlan? = null
    private var userSpeed = 1f

    /** Plan for (current, next), computed in the background once the current track's duration is known. */
    private fun autoPlan(): DjPlan? {
        val a = cur.player
        val c = a.currentMediaItem ?: return null
        val nextIndex = a.nextMediaItemIndex.takeIf { it >= 0 } ?: return null
        val next = a.getMediaItemAt(nextIndex)
        val dur = a.duration.takeIf { it > 0 } ?: return null
        val key = c.mediaId + "|" + next.mediaId + "|" + dur
        if (key != planKey) {
            planKey = key; plan = null
            val manual = settings.crossfadeSec.coerceAtLeast(2) * 1000L
            val tailStart = (dur - 40_000).coerceAtLeast(0)
            analysis.execute {
                // Loudness (100 ms) for the outro/silences, fine envelope (10 ms) for the tempo and the beats.
                val out = AudioAnalyzer.envelope(context, c.mediaId, tailStart, dur) ?: FloatArray(0)
                val inn = AudioAnalyzer.envelope(context, next.mediaId, 0, 15_000) ?: FloatArray(0)
                val outFine = AudioAnalyzer.envelope(context, c.mediaId, tailStart, dur, 10)
                val inFine = AudioAnalyzer.envelope(context, next.mediaId, 0, 20_000, 10)
                val outBeats = outFine?.let { fr.offlinetune.core.playback.BeatAnalyzer.analyze(it) }?.let { it.copy(firstBeatMs = it.firstBeatMs + tailStart) }
                val inBeats = inFine?.let { fr.offlinetune.core.playback.BeatAnalyzer.analyze(it) }
                val p = fr.offlinetune.core.playback.DjMix.plan(outBeats, inBeats, out, tailStart, dur, inn, manual)
                handler.post { if (planKey == key) plan = p }
            }
        }
        return plan
    }

    /** The fade begins: the prepared deck starts playing and becomes the current one; the other one fades out. */
    private fun start(p: Xf.Preparing, fadeMs: Long) {
        if (fadeMs < 300) { noRetryId = p.fromId; cancelCrossfade(); return }
        val a = cur
        val b = p.next
        userSpeed = a.player.playbackParameters.speed
        b.player.repeatMode = a.player.repeatMode
        // Mix automatique: incoming track at the outgoing tempo during the transition (pitch kept).
        val inSpeed = p.auto?.inSpeed ?: 1.0
        b.player.playbackParameters = PlaybackParameters((inSpeed * userSpeed).toFloat(), 1f)

        // The outgoing deck only finishes its song, on its own.
        a.player.removeListener(curListener)
        listeners.forEach { a.player.removeListener(it) }
        a.player.pauseAtEndOfMediaItems = true
        a.player.setAudioAttributes(AUDIO_ATTRIBUTES, false)
        a.player.setHandleAudioBecomingNoisy(false)
        a.tap = false

        b.player.setAudioAttributes(AUDIO_ATTRIBUTES, true)
        b.player.setHandleAudioBecomingNoisy(true)
        b.tap = true
        cur = b
        djPlan = p.auto
        if (p.auto != null) { b.bassCut = BASS_CUT; a.bassCut = 0f }
        xfadeGain = 0f; outGain = 1f
        xf = Xf.Fading(a, SystemClock.elapsedRealtime(), fadeMs)
        applyVolume()
        b.player.addListener(curListener)
        listeners.forEach { b.player.addListener(it) }
        b.player.play()
        onSwap(b.session)
    }

    private fun cancelCrossfade() {
        when (val s = xf) {
            is Xf.Preparing -> s.next.player.release()
            is Xf.Fading -> s.out.player.release()
            Xf.Idle -> {}
        }
        xf = Xf.Idle
        if (djPlan?.inSpeed?.let { it != 1.0 } == true) cur.player.playbackParameters = PlaybackParameters(userSpeed, 1f)
        djPlan = null
        cur.bassCut = 0f
        xfadeGain = 1f
        outGain = 1f
        applyVolume()
    }

    private fun playWithFade() {
        cancelFade()
        if (!settings.pauseFade) { fadeGain = 1f; applyVolume(); main.play(); return }
        fadeGain = 0f; applyVolume()
        main.play()
        animateFade(to = 1f) {}
    }

    /** Fades both decks down together, then pauses (the crossfade is dropped by the current deck's listener). */
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
        cur.player.release()
    }

    companion object {
        private const val PAUSE_FADE_MS = 250L
        private const val BASS_CUT = -24f
        private val AUDIO_ATTRIBUTES = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

        fun buildPlayer(context: Context, handleFocus: Boolean, tap: () -> Boolean = { false }, bassCut: () -> Float = { 0f }): ExoPlayer {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(DspAudioProcessor(bassCut, tap)))
                        // Tempo changes (Mix automatique) done by the audio output itself: smooth, no restart of
                        // the sound processing, so no hiccup while the tempo eases back.
                        .setEnableAudioTrackPlaybackParams(true)
                        .build()
            }
            return ExoPlayer.Builder(context, renderers)
                .setAudioAttributes(AUDIO_ATTRIBUTES, handleFocus)
                .setHandleAudioBecomingNoisy(handleFocus)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }
    }
}
