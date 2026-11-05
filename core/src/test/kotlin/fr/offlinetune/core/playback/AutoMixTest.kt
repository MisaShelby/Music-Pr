package fr.offlinetune.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class AutoMixTest {
    /** dB envelope (100 ms windows) built from segments of (seconds, level dB). */
    private fun env(vararg parts: Pair<Int, Float>) = parts.flatMap { (sec, db) -> List(sec * 10) { db } }.toFloatArray()

    @Test fun `trailing silence - transition ends where the music ends`() {
        // last 40 s of a 200 s track: 34 s loud, 6 s silence
        val p = AutoMix.plan(env(34 to -10f, 6 to -80f), tailStartMs = 160_000, durationMs = 200_000, inHead = env(15 to -10f), manualFadeMs = 6000)
        val musicEnd = 194_000L
        assertTrue(p.startAtMs + p.fadeMs <= musicEnd + 100, "$p")
        assertTrue(p.fadeMs in 2000..12000)
    }

    @Test fun `long quiet outro gives a longer fade starting at the outro`() {
        // 28 s body, 10 s fading outro (-28 dB), 2 s silence
        val p = AutoMix.plan(env(28 to -8f, 10 to -28f, 2 to -90f), 160_000, 200_000, env(15 to -9f), 6000)
        // outro starts at 160 s + 28 s = 188 s and lasts 10 s
        assertTrue(kotlin.math.abs(p.startAtMs - 188_000L) <= 300, "$p")
        assertTrue(kotlin.math.abs(p.fadeMs - 10_000L) <= 300, "$p")
    }

    @Test fun `leading silence of the next track is skipped`() {
        val p = AutoMix.plan(env(40 to -10f), 160_000, 200_000, env(2 to -85f, 13 to -9f), 6000)
        assertEquals(2000L, p.skipInMs)
    }

    @Test fun `loud ending without outro uses the manual fade at the end`() {
        val p = AutoMix.plan(env(40 to -9f), 160_000, 200_000, env(15 to -9f), 6000)
        assertEquals(6000L, p.fadeMs)
        assertEquals(194_000L, p.startAtMs)
    }

    @Test fun `no analysis falls back to manual fade`() {
        val p = AutoMix.plan(FloatArray(0), 0, 200_000, FloatArray(0), 5000)
        assertEquals(MixPlan(195_000, 5000, 0), p)
    }

    @Test fun `envelope of a sine is about -3 dB and silence very low`() {
        val rate = 8000
        val sine = FloatArray(rate) { sin(2 * PI * 440 * it / rate).toFloat() }
        val e = AutoMix.envelopeDb(sine + FloatArray(rate), rate)
        assertEquals(20, e.size)
        assertEquals(-3.0, e[2].toDouble(), 0.3)
        assertTrue(e[15] < -100)
    }
}
