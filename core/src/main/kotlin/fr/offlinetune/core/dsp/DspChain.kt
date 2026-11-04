package fr.offlinetune.core.dsp

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.tanh

@Serializable
data class DspSettings(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val bands: FloatArray = FloatArray(10),
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    /** -1 = left only … 0 = centre … 1 = right only. */
    val balance: Float = 0f,
    val mono: Boolean = false,
    val limiter: Boolean = true,
) {
    override fun equals(other: Any?) = other is DspSettings && enabled == other.enabled && preampDb == other.preampDb &&
        bands.contentEquals(other.bands) && bassDb == other.bassDb && trebleDb == other.trebleDb &&
        balance == other.balance && mono == other.mono && limiter == other.limiter

    override fun hashCode() = listOf(enabled, preampDb, bands.contentHashCode(), bassDb, trebleDb, balance, mono, limiter).hashCode()

    companion object {
        val FREQUENCIES = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        val LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
        const val MAX_DB = 12f
    }
}

/** The app's own sound processing: preamp → 10-band EQ → bass/treble shelves → mono → balance → soft limiter. */
class DspChain {
    private var settings = DspSettings()
    private var sampleRate = 0
    private var channels = 0
    private var filters: List<Biquad> = emptyList()
    private var active: List<Biquad> = emptyList()
    private var preamp = 1.0
    private var gainL = 1.0
    private var gainR = 1.0

    fun configure(s: DspSettings, sampleRate: Int, channels: Int) {
        if (sampleRate != this.sampleRate || channels != this.channels || filters.isEmpty()) {
            this.sampleRate = sampleRate; this.channels = channels
            filters = List(12) { Biquad(channels) }
        }
        settings = s
        val nyquistLimit = 0.45 * sampleRate
        val list = mutableListOf<Biquad>()
        DspSettings.FREQUENCIES.forEachIndexed { i, f ->
            val g = s.bands.getOrElse(i) { 0f }.coerceIn(-DspSettings.MAX_DB, DspSettings.MAX_DB)
            if (g != 0f && f < nyquistLimit) list += filters[i].apply { set(Biquad.Type.PEAK, f, g.toDouble(), 1.41, sampleRate) }
        }
        if (s.bassDb != 0f) list += filters[10].apply { set(Biquad.Type.LOW_SHELF, 100.0, s.bassDb.toDouble(), 0.707, sampleRate) }
        if (s.trebleDb != 0f && 10000.0 < nyquistLimit) list += filters[11].apply { set(Biquad.Type.HIGH_SHELF, 10000.0, s.trebleDb.toDouble(), 0.707, sampleRate) }
        active = list
        preamp = 10.0.pow(s.preampDb / 20.0)
        val b = s.balance.coerceIn(-1f, 1f).toDouble()
        gainL = if (b > 0) cos(b * PI / 2) else 1.0
        gainR = if (b < 0) cos(-b * PI / 2) else 1.0
    }

    /** In-place processing of [frames] interleaved frames. */
    fun process(buf: FloatArray, frames: Int) {
        val s = settings
        if (!s.enabled || channels == 0) return
        val ch = channels
        val stereo = ch == 2
        for (f in 0 until frames) {
            val base = f * ch
            for (c in 0 until ch) {
                var x = buf[base + c] * preamp
                for (flt in active) x = flt.process(x, c)
                buf[base + c] = x.toFloat()
            }
            if (stereo) {
                var l = buf[base].toDouble(); var r = buf[base + 1].toDouble()
                if (s.mono) { val m = (l + r) / 2; l = m; r = m }
                l *= gainL; r *= gainR
                if (s.limiter) { l = limit(l); r = limit(r) }
                buf[base] = l.toFloat(); buf[base + 1] = r.toFloat()
            } else if (s.limiter) {
                for (c in 0 until ch) buf[base + c] = limit(buf[base + c].toDouble()).toFloat()
            }
        }
    }

    fun reset() = filters.forEach { it.reset() }

    private fun limit(x: Double): Double {
        val ax = abs(x)
        if (ax <= THRESHOLD) return x
        val y = THRESHOLD + (1 - THRESHOLD) * tanh((ax - THRESHOLD) / (1 - THRESHOLD))
        return if (x < 0) -y else y
    }

    companion object {
        /** −1 dBFS. */
        private const val THRESHOLD = 0.8912509381337456
    }
}
