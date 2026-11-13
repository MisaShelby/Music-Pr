package fr.offlinetune.core.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** Tempo of a passage: [firstBeatMs] is the position (ms, from the start of the analysed passage) of a beat. */
data class Beats(val bpm: Double, val firstBeatMs: Double, val confidence: Double) {
    val beatMs get() = 60_000.0 / bpm
}

/** Beat tracking on a 10 ms loudness envelope (dB): onset strength → autocorrelation → comb phase. */
object BeatAnalyzer {
    const val WINDOW_MS = 10.0
    private const val MIN_BPM = 70.0
    private const val MAX_BPM = 180.0
    private const val MIN_CONFIDENCE = 1.6

    fun analyze(envDb: FloatArray): Beats? {
        if (envDb.size < 400) return null // < 4 s
        val onset = DoubleArray(envDb.size) { i -> if (i == 0) 0.0 else max(0.0, (envDb[i] - envDb[i - 1]).toDouble()) }
        val minLag = (60_000 / (MAX_BPM * WINDOW_MS)).toInt()
        val maxLag = (60_000 / (MIN_BPM * WINDOW_MS)).toInt() + 1
        val r = DoubleArray(maxLag + 2)
        for (lag in minLag - 1..maxLag + 1) {
            var s = 0.0
            for (i in 0 until onset.size - lag) s += onset[i] * onset[i + lag]
            r[lag] = s
        }
        val best = (minLag..maxLag).maxByOrNull { r[it] } ?: return null
        val mean = (minLag..maxLag).map { r[it] }.average()
        if (mean <= 0 || r[best] / mean < MIN_CONFIDENCE) return null
        // Parabolic refinement of the peak for sub-window precision.
        val a = r[best - 1]; val b = r[best]; val c = r[best + 1]
        val denom = a - 2 * b + c
        val lag = if (denom != 0.0) best + 0.5 * (a - c) / denom else best.toDouble()
        var bpm = 60_000 / (lag * WINDOW_MS)
        while (bpm < 80) bpm *= 2
        while (bpm > 160 && bpm / 2 >= 80) bpm /= 2
        // Phase: offset that collects the most onset energy on the beat grid.
        val period = 60_000 / bpm / WINDOW_MS
        var bestPhase = 0; var bestSum = -1.0
        for (phase in 0 until ceil(period).toInt()) {
            var s = 0.0; var k = 0
            while (true) {
                val idx = (phase + k * period).roundToLong().toInt()
                if (idx >= onset.size) break
                s += onset[idx]; k++
            }
            if (s > bestSum) { bestSum = s; bestPhase = phase }
        }
        return Beats(bpm, bestPhase * WINDOW_MS, r[best] / mean)
    }
}

/** DJ-style transition: beat-aligned start, length in beats, tempo match of the incoming track, bass swap at the middle. */
data class DjPlan(val startAtMs: Long, val fadeMs: Long, val inStartMs: Long, val inSpeed: Double, val beatMs: Double?)

object DjMix {
    private const val MAX_STRETCH = 0.08

    /**
     * [outBeats.firstBeatMs] is absolute in the outgoing track; [inBeats.firstBeatMs] absolute in the incoming track.
     * Without a reliable tempo, the loudness plan ([AutoMix]) is used.
     */
    fun plan(
        outBeats: Beats?, inBeats: Beats?, outTail: FloatArray, tailStartMs: Long, durationMs: Long,
        inHead: FloatArray, manualFadeMs: Long,
    ): DjPlan {
        val auto = AutoMix.plan(outTail, tailStartMs, durationMs, inHead, manualFadeMs)
        if (outBeats == null) return DjPlan(auto.startAtMs, auto.fadeMs, auto.skipInMs, 1.0, null)
        val musicEnd = auto.startAtMs + auto.fadeMs
        val beat = outBeats.beatMs
        val beats = if (16 * beat <= 12_000) 16 else 8
        val fade = (beats * beat).roundToLong()
        // Last beat of the outgoing grid that leaves room for the whole transition before the music ends.
        val k = floor((musicEnd - fade - outBeats.firstBeatMs) / beat)
        val start = (outBeats.firstBeatMs + k * beat).roundToLong().coerceAtLeast(0)
        val speed = if (inBeats != null && abs(outBeats.bpm / inBeats.bpm - 1) <= MAX_STRETCH) outBeats.bpm / inBeats.bpm else 1.0
        val inStart = if (inBeats != null) {
            val ib = inBeats.beatMs
            val n = ceil((auto.skipInMs - inBeats.firstBeatMs) / ib).coerceAtLeast(0.0)
            (inBeats.firstBeatMs + n * ib).roundToLong()
        } else auto.skipInMs
        return DjPlan(start, fade, inStart, speed, beat)
    }
}
