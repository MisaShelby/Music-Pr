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

/** Runs the app's own [DspChain] on decoded PCM (16-bit or float), inside ExoPlayer's audio sink. */
@UnstableApi
class DspAudioProcessor : BaseAudioProcessor() {
    private val chain = DspChain()
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

        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        if (float) for (i in 0 until samples) out.putFloat(scratch[i])
        else for (i in 0 until samples) out.putShort((scratch[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        out.flip()
    }

    override fun onFlush() = chain.reset()
    override fun onReset() { chain.reset(); applied = null }
}
