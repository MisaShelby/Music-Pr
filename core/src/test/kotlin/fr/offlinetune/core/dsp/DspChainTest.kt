package fr.offlinetune.core.dsp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DspChainTest {
    private val sr = 48000

    /** Interleaved stereo: left = sine(freq, amp), right = sine or silence. */
    private fun stereo(freq: Double, amp: Float = 0.25f, seconds: Double = 1.0, rightToo: Boolean = true): FloatArray {
        val n = (sr * seconds).toInt()
        return FloatArray(n * 2) { i ->
            val s = (amp * sin(2 * PI * freq * (i / 2) / sr)).toFloat()
            if (i % 2 == 0 || rightToo) s else 0f
        }
    }

    private fun rms(buf: FloatArray, channel: Int, skipFrames: Int = sr / 5): Double {
        var sum = 0.0; var n = 0
        var i = skipFrames * 2 + channel
        while (i < buf.size) { sum += buf[i] * buf[i]; n++; i += 2 }
        return sqrt(sum / n)
    }

    private fun gainDb(settings: DspSettings, freq: Double, channel: Int = 0): Double {
        val input = stereo(freq)
        val out = input.copyOf()
        DspChain().apply { configure(settings, sr, 2) }.process(out, out.size / 2)
        return 20 * log10(rms(out, channel) / rms(input, channel))
    }

    @Test fun `flat settings without limiter are transparent`() {
        val input = stereo(440.0, 0.9f)
        val out = input.copyOf()
        DspChain().apply { configure(DspSettings(limiter = false), sr, 2) }.process(out, out.size / 2)
        input.indices.forEach { assertEquals(input[it], out[it], 1e-4f) }
    }

    @Test fun `plus 12 dB at 1 kHz boosts 1 kHz and leaves 100 Hz alone`() {
        val bands = FloatArray(10).also { it[5] = 12f }
        val s = DspSettings(bands = bands, limiter = false)
        assertEquals(12.0, gainDb(s, 1000.0), 0.5)
        assertEquals(0.0, gainDb(s, 100.0), 0.5)
    }

    @Test fun `bass shelf boosts low frequencies`() {
        val s = DspSettings(bassDb = 6f, limiter = false)
        assertEquals(6.0, gainDb(s, 30.0), 1.0)
        assertEquals(0.0, gainDb(s, 5000.0), 0.5)
    }

    @Test fun `treble shelf boosts high frequencies`() {
        assertEquals(6.0, gainDb(DspSettings(trebleDb = 6f, limiter = false), 16000.0), 1.0)
    }

    @Test fun `preamp is a plain gain`() {
        assertEquals(-6.0, gainDb(DspSettings(preampDb = -6f, limiter = false), 440.0), 0.05)
    }

    @Test fun `balance fully left silences the right channel`() {
        val out = stereo(440.0)
        DspChain().apply { configure(DspSettings(balance = -1f), sr, 2) }.process(out, out.size / 2)
        assertTrue(rms(out, 1) < 1e-6)
        assertEquals(rms(stereo(440.0), 0), rms(out, 0), 1e-4)
    }

    @Test fun `mono copies the mix to both channels`() {
        val out = stereo(440.0, rightToo = false)
        DspChain().apply { configure(DspSettings(mono = true, limiter = false), sr, 2) }.process(out, out.size / 2)
        for (f in 0 until out.size / 2) assertEquals(out[2 * f], out[2 * f + 1], 1e-7f)
    }

    @Test fun `limiter keeps a boosted full scale sine under 1`() {
        val out = stereo(440.0, 1.0f)
        DspChain().apply { configure(DspSettings(preampDb = 12f, limiter = true), sr, 2) }.process(out, out.size / 2)
        assertTrue(out.maxOf { abs(it) } <= 1.0f)
    }

    @Test fun `disabled chain does nothing`() {
        val input = stereo(440.0, 0.9f)
        val out = input.copyOf()
        DspChain().apply { configure(DspSettings(enabled = false, preampDb = 12f), sr, 2) }.process(out, out.size / 2)
        assertTrue(input.contentEquals(out))
    }

    @Test fun `changing settings does not reset filter state`() {
        val chain = DspChain().apply { configure(DspSettings(bands = FloatArray(10) { 6f }), sr, 2) }
        val a = stereo(440.0, seconds = 0.1); chain.process(a, a.size / 2)
        chain.configure(DspSettings(bands = FloatArray(10) { 5f }), sr, 2)
        val b = stereo(440.0, seconds = 0.1); chain.process(b, b.size / 2)
        // No click: first samples after the change stay in the range of the signal.
        assertTrue(b.take(200).maxOf { abs(it) } < 1.0f)
    }

    @Test fun `presets have 10 values within 12 dB`() {
        assertEquals(setOf("Plat", "Rock", "Pop", "Jazz", "Classique", "Électro", "Hip-Hop", "Bass boost", "Voix", "Acoustique"), Presets.builtIn.keys)
        Presets.builtIn.values.forEach { v -> assertEquals(10, v.size); assertTrue(v.all { it in -12f..12f }) }
    }

    @Test fun `tone switch off ignores bass and treble but keeps their values`() {
        val s = DspSettings(bassDb = 9f, trebleDb = 9f, toneEnabled = false, limiter = false)
        assertEquals(0.0, gainDb(s, 30.0), 0.05)
        assertEquals(9f, s.bassDb)
    }

    @Test fun `eq switch off ignores the bands only`() {
        val s = DspSettings(bands = FloatArray(10) { 6f }, eqEnabled = false, bassDb = 6f, limiter = false)
        assertEquals(0.0, gainDb(s, 1000.0), 0.3)
        assertEquals(6.0, gainDb(s, 30.0), 1.0)
    }
}
