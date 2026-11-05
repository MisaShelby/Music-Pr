package fr.offlinetune.core.dsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {
    private val rate = 44_100

    @Test fun `a 1 kHz sine lights the band that contains 1 kHz`() {
        val x = FloatArray(Spectrum.SIZE) { (0.5 * sin(2 * PI * 1000 * it / rate)).toFloat() }
        val bands = Spectrum.bands(x, rate, 24)
        val peak = bands.indices.maxBy { bands[it] }
        val (lo, hi) = Spectrum.bandRange(peak, 24)
        assertTrue(1000.0 in lo..hi, "peak band $peak = $lo..$hi")
        assertTrue(bands[peak] > 0.6f)
        assertTrue(bands.filterIndexed { i, _ -> kotlin.math.abs(i - peak) > 2 }.all { it < 0.35f }, bands.joinToString())
    }

    @Test fun `silence gives zero bands`() {
        assertTrue(Spectrum.bands(FloatArray(Spectrum.SIZE), rate, 24).all { it == 0f })
    }

    @Test fun `waveform bars are normalised and downsampled`() {
        val env = FloatArray(1000) { if (it < 500) -60f else -6f }
        val bars = Waveform.bars(env, 10)
        assertEquals(10, bars.size)
        assertTrue(bars.take(5).all { it < 0.05f }, bars.joinToString())
        assertTrue(bars.drop(5).all { it > 0.85f }, bars.joinToString())
    }
}
