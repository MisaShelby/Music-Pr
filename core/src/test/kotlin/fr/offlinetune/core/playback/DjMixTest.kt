package fr.offlinetune.core.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DjMixTest {
    /** 10 ms energy envelope (dB) of a click track: loud short hits on each beat over a quiet bed. */
    private fun clicks(bpm: Double, seconds: Int, offsetMs: Int = 0, bed: Float = -40f, hit: Float = -6f): FloatArray {
        val n = seconds * 100
        val period = 60000.0 / bpm
        return FloatArray(n) { i ->
            val t = i * 10.0 - offsetMs
            val phase = ((t % period) + period) % period
            if (phase < 30) hit else bed
        }
    }

    @Test fun `detects 120 bpm and its phase`() {
        val b = BeatAnalyzer.analyze(clicks(120.0, 20, offsetMs = 130))!!
        assertEquals(120.0, b.bpm, 1.0)
        val period = 500.0
        val err = ((b.firstBeatMs - 130) % period + period) % period
        assertTrue(err < 30 || err > period - 30, "$b")
    }

    @Test fun `detects 128 bpm`() {
        assertEquals(128.0, BeatAnalyzer.analyze(clicks(128.0, 20))!!.bpm, 1.5)
    }

    @Test fun `half and double tempo are folded into 80-160`() {
        assertEquals(140.0, BeatAnalyzer.analyze(clicks(70.0 * 2, 20))!!.bpm, 1.5)
        val b = BeatAnalyzer.analyze(clicks(170.0, 20))!!
        assertTrue(b.bpm in 80.0..170.5, "$b")
    }

    @Test fun `noise has no reliable tempo`() {
        val r = Random(3)
        assertNull(BeatAnalyzer.analyze(FloatArray(2000) { -30f + r.nextFloat() * 20f }))
    }

    @Test fun `dj plan - 16 beats at 120 bpm, on the beat grid, before the music ends`() {
        val outBeats = Beats(120.0, firstBeatMs = 170_000 + 100.0, confidence = 1.0)
        val inBeats = Beats(118.0, firstBeatMs = 250.0, confidence = 1.0)
        val loud = FloatArray(400) { -10f } // 40 s tail at 100 ms windows, music until the end
        val p = DjMix.plan(outBeats, inBeats, loud, tailStartMs = 160_000, durationMs = 200_000, inHead = FloatArray(150) { -10f }, manualFadeMs = 6000)
        assertEquals(8000L, p.fadeMs)
        assertTrue(p.startAtMs + p.fadeMs <= 200_000, "$p")
        val fromGrid = ((p.startAtMs - 170_100.0) % 500.0 + 500.0) % 500.0
        assertTrue(fromGrid < 1.0 || fromGrid > 499.0, "$p")
        assertEquals(120.0 / 118.0, p.inSpeed, 0.001)
        assertEquals(250L, p.inStartMs)
    }

    @Test fun `tempos too far apart - no stretch`() {
        val p = DjMix.plan(Beats(120.0, 0.0, 1.0), Beats(140.0, 0.0, 1.0), FloatArray(400) { -10f }, 160_000, 200_000, FloatArray(150) { -10f }, 6000)
        assertEquals(1.0, p.inSpeed, 1e-9)
    }

    @Test fun `no tempo falls back to the loudness plan`() {
        val tail = FloatArray(400) { if (it < 340) -10f else -90f }
        val p = DjMix.plan(null, null, tail, 160_000, 200_000, FloatArray(150) { -10f }, 6000)
        val auto = AutoMix.plan(tail, 160_000, 200_000, FloatArray(150) { -10f }, 6000)
        assertEquals(auto.startAtMs, p.startAtMs); assertEquals(auto.fadeMs, p.fadeMs); assertEquals(1.0, p.inSpeed, 1e-9)
    }
}
