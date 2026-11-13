package fr.offlinetune.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Pure fade maths (unit-tested on the JVM). */
object Fades {
    /** Equal-power crossfade at progress [t] (0..1): (incoming gain, outgoing gain). */
    fun crossfadeGains(t: Float): Pair<Float, Float> {
        val x = t.coerceIn(0f, 1f)
        if (x == 0f) return 0f to 1f
        if (x == 1f) return 1f to 0f
        return sin(x * PI / 2).toFloat() to cos(x * PI / 2).toFloat()
    }

    /** Time given to the outgoing player to load and seek before the fade must begin. */
    const val PREPARE_LEAD_MS = 2500L

    fun shouldPrepareCrossfade(durationMs: Long, positionMs: Long, crossfadeMs: Long): Boolean =
        shouldStartCrossfade(durationMs, positionMs, crossfadeMs + PREPARE_LEAD_MS) && durationMs >= 2 * crossfadeMs

    fun shouldStartCrossfade(durationMs: Long, positionMs: Long, crossfadeMs: Long): Boolean {
        if (durationMs <= 0 || crossfadeMs <= 0) return false
        if (durationMs < 2 * crossfadeMs) return false
        return durationMs - positionMs in 1..crossfadeMs
    }
}
