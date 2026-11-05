package fr.offlinetune.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ "Audio EQ Cookbook" biquad, transposed direct form II, one state pair per channel. */
class Biquad(channels: Int) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    enum class Type { PEAK, LOW_SHELF, HIGH_SHELF }

    /** Recomputes coefficients; filter state is kept so live changes do not click. */
    fun set(type: Type, freq: Double, gainDb: Double, q: Double, sampleRate: Int) {
        val a = 10.0.pow(gainDb / 40)
        val w0 = 2 * PI * freq / sampleRate
        val cw = cos(w0); val sw = sin(w0)
        val nb0: Double; val nb1: Double; val nb2: Double; val na0: Double; val na1: Double; val na2: Double
        when (type) {
            Type.PEAK -> {
                val alpha = sw / (2 * q)
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
            Type.LOW_SHELF -> {
                val beta = 2 * sqrt(a) * (sw / 2 * sqrt(2.0)) // shelf slope S = 1
                nb0 = a * ((a + 1) - (a - 1) * cw + beta); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - beta)
                na0 = (a + 1) + (a - 1) * cw + beta; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - beta
            }
            Type.HIGH_SHELF -> {
                val beta = 2 * sqrt(a) * (sw / 2 * sqrt(2.0))
                nb0 = a * ((a + 1) + (a - 1) * cw + beta); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - beta)
                na0 = (a + 1) - (a - 1) * cw + beta; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - beta
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun process(x: Double, ch: Int): Double {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }

    fun reset() { z1.fill(0.0); z2.fill(0.0) }
}
