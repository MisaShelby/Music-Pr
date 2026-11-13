package fr.offlinetune.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** Live spectrum for the player visualizer: Hann-windowed FFT folded into log-spaced bands, 0..1 each. */
object Spectrum {
    const val SIZE = 2048
    private const val LOW_HZ = 40.0
    private const val HIGH_HZ = 16_000.0
    private const val FLOOR_DB = -70.0
    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1)) }

    fun bandRange(i: Int, n: Int): Pair<Double, Double> {
        val r = HIGH_HZ / LOW_HZ
        return LOW_HZ * r.pow(i.toDouble() / n) to LOW_HZ * r.pow((i + 1).toDouble() / n)
    }

    /** [samples]: the last [SIZE] mono samples in [-1, 1]. */
    fun bands(samples: FloatArray, rate: Int, n: Int): FloatArray {
        val re = DoubleArray(SIZE) { if (it < samples.size) samples[it] * window[it] else 0.0 }
        val im = DoubleArray(SIZE)
        fft(re, im)
        val binHz = rate.toDouble() / SIZE
        val scale = SIZE / 4.0 // full-scale sine with a Hann window
        return FloatArray(n) { b ->
            val (lo, hi) = bandRange(b, n)
            val from = (lo / binHz).toInt().coerceIn(1, SIZE / 2 - 1)
            val to = (hi / binHz).toInt().coerceIn(from, SIZE / 2 - 1)
            var m = 0.0
            for (k in from..to) m = maxOf(m, hypot(re[k], im[k]))
            if (m <= 0.0) 0f else (((20 * log10(m / scale)) - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { re[i] = re[j].also { re[j] = re[i] }; im[i] = im[j].also { im[j] = im[i] } }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Static waveform of a whole track (for the « Barres / Miroir / Onde » visualizers). */
object Waveform {
    private const val FLOOR_DB = -50f

    /** [envDb]: loudness per window over the whole track → [n] bars in 0..1. */
    fun bars(envDb: FloatArray, n: Int): FloatArray {
        if (envDb.isEmpty() || n <= 0) return FloatArray(n)
        return FloatArray(n) { b ->
            val from = (b.toLong() * envDb.size / n).toInt()
            val to = ((b + 1L) * envDb.size / n).toInt().coerceAtLeast(from + 1).coerceAtMost(envDb.size)
            var m = FLOOR_DB
            for (i in from until to) m = maxOf(m, envDb[i])
            ((m - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}
