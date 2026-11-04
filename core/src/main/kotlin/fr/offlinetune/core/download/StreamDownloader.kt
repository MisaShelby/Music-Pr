package fr.offlinetune.core.download

import fr.offlinetune.core.meta.ResolvedStream
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Downloads an audio stream byte for byte (no re-encoding).
 * Progressive streams: sequential Range requests (avoids YouTube throttling), retried per chunk.
 * HLS: init segment (EXT-X-MAP) + media segments concatenated in order.
 */
class StreamDownloader(
    private val chunkSize: Long = 8L * 1024 * 1024,
    private val retries: Int = 3,
    private val retryDelayMs: Long = 1000,
) {
    suspend fun download(stream: ResolvedStream, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        try {
            dest.outputStream().buffered().use { out ->
                if (stream.isHls) hls(stream.url, out, onProgress) else progressive(stream.url, stream.contentLength, out, onProgress)
            }
            onProgress(1f)
        } catch (e: Throwable) {
            dest.delete()
            throw if (e is IOException) NetworkLost(e) else e
        }
    }

    private suspend fun progressive(url: String, knownLength: Long?, out: OutputStream, onProgress: (Float) -> Unit) {
        var total = knownLength
        var pos = 0L
        while (total == null || pos < total) {
            coroutineContext.ensureActive()
            val end = pos + chunkSize - 1
            val (bytes, reportedTotal) = withRetry { fetchRange(url, pos, end) }
            if (total == null) total = reportedTotal ?: (pos + bytes.size)
            if (bytes.isEmpty()) break
            out.write(bytes)
            pos += bytes.size
            onProgress((pos.toFloat() / total).coerceAtMost(0.999f))
            if (reportedTotal == null && bytes.size < chunkSize) break // server ignored Range: whole body received
        }
    }

    /** Returns the chunk bytes and the total size from Content-Range (null if unknown). */
    private fun fetchRange(url: String, start: Long, end: Long): Pair<ByteArray, Long?> {
        val req = Http.request(url, mapOf("Range" to "bytes=$start-$end")).build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 416) return ByteArray(0) to start
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val expected = resp.header("Content-Length")?.toLongOrNull()
            val bytes = body.bytes()
            if (expected != null && bytes.size.toLong() != expected) throw IOException("truncated chunk")
            val total = resp.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            if (resp.code == 200 && start > 0) throw IOException("server ignored Range")
            return bytes to (total ?: if (resp.code == 200) bytes.size.toLong() else null)
        }
    }

    private suspend fun hls(url: String, out: OutputStream, onProgress: (Float) -> Unit) {
        val playlist = Hls.parse(withRetry { Http.getText(url) }, url)
        val parts = listOfNotNull(playlist.initUrl) + playlist.segments
        if (parts.isEmpty()) throw IOException("empty HLS playlist")
        parts.forEachIndexed { i, part ->
            coroutineContext.ensureActive()
            out.write(withRetry { Http.getBytes(part) })
            onProgress(((i + 1).toFloat() / parts.size).coerceAtMost(0.999f))
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(retries) { attempt ->
            try {
                return block()
            } catch (e: IOException) {
                last = e
            } catch (e: NetworkLost) {
                last = e
            }
            if (attempt < retries - 1) delay(retryDelayMs * (attempt + 1))
        }
        throw NetworkLost(last)
    }
}
