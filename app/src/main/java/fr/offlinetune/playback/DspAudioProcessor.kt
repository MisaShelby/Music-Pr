package fr.offlinetune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import fr.offlinetune.core.dsp.DspChain
import fr.offlinetune.core.dsp.DspSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shared, live-updated sound settings read by every player's processor. */
object DspState {
    @Volatile var settings: DspSettings = DspSettings()
}

/** Last mono samples played by the main player, for the « Spectre » visualizer (no microphone involved). */
object LiveSpectrum {
    private val ring = FloatArray(fr.offlinetune.core.dsp.Spectrum.SIZE)
    private var pos = 0
    @Volatile private var rate = 0

    @Synchronized fun push(samples: FloatArray, count: Int, channels: Int, sampleRate: Int) {
        rate = sampleRate
        var i = 0
        while (i + channels <= count) {
            var m = 0f
            for (c in 0 until channels) m += samples[i + c]
            ring[pos] = m / channels
            pos = (pos + 1) % ring.size
            i += channels
        }
    }

    @Synchronized fun snapshot(): Pair<FloatArray, Int> {
        val out = FloatArray(ring.size) { ring[(pos + it) % ring.size] }
        return out to rate
    }
}

/** Runs the app's own [DspChain] on decoded PCM (16-bit or float), inside ExoPlayer's audio sink. */
@UnstableApi
class DspAudioProcessor(private val bassCutDb: () -> Float = { 0f }, private val tap: () -> Boolean = { false }) : BaseAudioProcessor() {
    private val chain = DspChain()
    // DJ bass swap: extra low shelf (150 Hz) driven by the crossfade controller.
    private var shelf: fr.offlinetune.core.dsp.Biquad? = null
    private var shelfDb = 0f
    private var applied: DspSettings? = null
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        applied = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val fmt = inputAudioFormat
        val s = DspState.settings
        if (s != applied) { chain.configure(s, fmt.sampleRate, fmt.channelCount); applied = s }

        val float = fmt.encoding == C.ENCODING_PCM_FLOAT
        val samples = if (float) remaining / 4 else remaining / 2
        if (scratch.size < samples) scratch = FloatArray(samples)
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until samples) scratch[i] = input.getFloat()
        else for (i in 0 until samples) scratch[i] = input.getShort() / 32768f

        if (s.enabled) chain.process(scratch, samples / fmt.channelCount)
        val cut = bassCutDb()
        if (cut < -0.1f) {
            val ch = fmt.channelCount
            val b = shelf ?: fr.offlinetune.core.dsp.Biquad(ch).also { shelf = it }
            if (kotlin.math.abs(cut - shelfDb) > 0.5f) { b.set(fr.offlinetune.core.dsp.Biquad.Type.LOW_SHELF, 150.0, cut.toDouble(), 0.707, fmt.sampleRate); shelfDb = cut }
            for (f in 0 until samples / ch) for (c in 0 until ch) { val i = f * ch + c; scratch[i] = b.process(scratch[i].toDouble(), c).toFloat() }
        } else if (shelf != null) { shelf = null; shelfDb = 0f }

        if (tap()) LiveSpectrum.push(scratch, samples, fmt.channelCount, fmt.sampleRate)
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until samples) out.putFloat(scratch[i])
        else for (i in 0 until samples) out.putShort((scratch[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        out.flip()
    }

    override fun onFlush() = chain.reset()
    override fun onReset() { chain.reset(); applied = null }
}
