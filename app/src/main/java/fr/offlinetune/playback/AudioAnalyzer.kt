package fr.offlinetune.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import fr.offlinetune.core.playback.AutoMix
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Decodes a time range of a local file and returns its loudness envelope (dB per 100 ms, mono). */
object AudioAnalyzer {
    private val cache = LruCache<String, FloatArray>(24)

    fun envelope(context: Context, uri: String, startMs: Long, endMs: Long, windowMs: Long = AutoMix.WINDOW_MS): FloatArray? {
        val key = "$uri@$startMs-$endMs/$windowMs"
        cache.get(key)?.let { return it }
        val result = runCatching { decode(context, uri, startMs, endMs, windowMs) }.getOrNull() ?: return null
        cache.put(key, result)
        return result
    }

    private fun decode(context: Context, uri: String, startMs: Long, endMs: Long, windowMs: Long): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            codec = MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val windows = ((endMs - startMs) / windowMs).toInt().coerceAtLeast(1)
            val sums = DoubleArray(windows); val counts = IntArray(windows)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var done = false
            val deadline = System.currentTimeMillis() + 15000
            while (!done && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > endMs * 1000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frameBytes = channels * (if (isFloat) 4 else 2)
                        val frames = info.size / frameBytes
                        for (f in 0 until frames) {
                            var mono = 0.0
                            repeat(channels) { mono += if (isFloat) buf.getFloat().toDouble() else buf.getShort() / 32768.0 }
                            mono /= channels
                            val tMs = info.presentationTimeUs / 1000 + f * 1000L / rate
                            val w = ((tMs - startMs) / windowMs).toInt()
                            if (w in 0 until windows) { sums[w] += mono * mono; counts[w]++ }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            return FloatArray(windows) { w -> if (counts[w] == 0) -120f else (20 * log10(sqrt(sums[w] / counts[w]) + 1e-9)).toFloat() }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; ex.release()
        }
    }
}
