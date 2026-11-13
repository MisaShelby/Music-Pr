package fr.offlinetune.core.playback

import kotlin.math.log10
import kotlin.math.sqrt

/** When to start the transition (ms in the outgoing track), how long it lasts, and where the next track starts. */
data class MixPlan(val startAtMs: Long, val fadeMs: Long, val skipInMs: Long)

/**
 * « Mix automatique »: chooses the transition from the sound itself (loudness envelopes), not from a fixed time.
 * - the music really ends at the last window above the silence floor (trailing silence is not played);
 * - the outro starts where the level falls 12 dB below the track's body and stays there;
 * - the fade covers the outro (2–12 s); a loud ending without outro uses the user's manual fade;
 * - the next track's leading silence is skipped (≤ 10 s).
 */
object AutoMix {
    const val WINDOW_MS = 100L
    private const val MIN_FADE = 2000L
    private const val MAX_FADE = 12000L
    private const val FLOOR_DB = -55f

    /** RMS level in dB per [windowMs] window of mono PCM in [-1, 1]. */
    fun envelopeDb(pcm: FloatArray, rate: Int, windowMs: Long = WINDOW_MS): FloatArray {
        val win = (rate * windowMs / 1000).toInt().coerceAtLeast(1)
        return FloatArray(pcm.size / win) { w ->
            var sum = 0.0
            for (i in w * win until (w + 1) * win) sum += pcm[i] * pcm[i]
            (20 * log10(sqrt(sum / win) + 1e-9)).toFloat()
        }
    }

    private fun reference(env: FloatArray): Float {
        val sorted = env.sorted()
        return sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.lastIndex)] // loud body level (90th percentile)
    }

    fun plan(outTail: FloatArray, tailStartMs: Long, durationMs: Long, inHead: FloatArray, manualFadeMs: Long): MixPlan {
        val manual = manualFadeMs.coerceIn(MIN_FADE, MAX_FADE)
        if (outTail.isEmpty()) return MixPlan((durationMs - manual).coerceAtLeast(0), manual, skipIn(inHead))
        val ref = reference(outTail)
        val silence = maxOf(ref - 36f, FLOOR_DB)
        val lastSound = outTail.indexOfLast { it > silence }
        if (lastSound < 0) return MixPlan((durationMs - manual).coerceAtLeast(0), manual, skipIn(inHead))
        val musicEnd = tailStartMs + (lastSound + 1) * WINDOW_MS
        val lastBody = (0..lastSound).lastOrNull { outTail[it] >= ref - 12f } ?: lastSound
        val outroStart = tailStartMs + (lastBody + 1) * WINDOW_MS
        val outro = musicEnd - outroStart
        val fade = if (outro >= MIN_FADE) outro.coerceAtMost(MAX_FADE) else manual
        val start = (musicEnd - fade).coerceAtLeast(0)
        return MixPlan(start, fade, skipIn(inHead))
    }

    private fun skipIn(inHead: FloatArray): Long {
        if (inHead.isEmpty()) return 0
        val ref = reference(inHead)
        val first = inHead.indexOfFirst { it > maxOf(ref - 36f, FLOOR_DB) }
        return if (first <= 0) 0 else (first * WINDOW_MS).coerceAtMost(10_000)
    }
}
