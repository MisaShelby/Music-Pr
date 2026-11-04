package fr.offlinetune.core.download

import fr.offlinetune.core.meta.ResolvedStream
import fr.offlinetune.core.model.AudioFormat
import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.NetworkLost
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

class StreamDownloaderTest {
    @TempDir lateinit var tmp: File
    private val fmt = AudioFormat(Container.OGG_OPUS, "Opus", 160)
    private val data = Random(42).nextBytes(20 * 1024 * 1024 + 123)

    private fun rangeServer(failFrom: Long? = null) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                val start = range[0].toLong()
                val end = minOf(range[1].toLong(), data.size - 1L)
                if (failFrom != null && start >= failFrom) {
                    return MockResponse().setResponseCode(206).setBody(Buffer().write(data, start.toInt(), 1000))
                        .setHeader("Content-Length", end - start + 1)
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                    .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
            }
        }
    }

    @Test fun `ranged download reassembles the file with monotonic progress`() = runBlocking {
        rangeServer().use { s ->
            val dest = File(tmp, "out.bin")
            val progress = mutableListOf<Float>()
            StreamDownloader(chunkSize = 8 * 1024 * 1024).download(
                ResolvedStream(s.url("/a").toString(), fmt, 10, false, null), dest,
            ) { progress += it }
            assertArrayEquals(data, dest.readBytes())
            assertEquals(1.0f, progress.last())
            assertEquals(progress.sorted(), progress)
            assertEquals(3, s.requestCount)
        }
    }

    @Test fun `connection lost mid download throws NetworkLost and leaves no file`() {
        rangeServer(failFrom = 8L * 1024 * 1024).use { s ->
            val dest = File(tmp, "out.bin")
            assertThrows<NetworkLost> {
                runBlocking {
                    StreamDownloader(chunkSize = 8 * 1024 * 1024, retryDelayMs = 1)
                        .download(ResolvedStream(s.url("/a").toString(), fmt, 10, false, null), dest) {}
                }
            }
            assertFalse(dest.exists())
        }
    }

    @Test fun `unwritable destination is a storage error, not a network error`() {
        rangeServer().use { s ->
            val dest = File(tmp, "is-a-dir").apply { mkdirs() } // cannot be opened as a file
            assertThrows<fr.offlinetune.core.model.StorageError> {
                runBlocking { StreamDownloader().download(ResolvedStream(s.url("/a").toString(), fmt, 10, false, null), dest) {} }
            }
        }
    }

    @Test fun `hls playlist with init segment is concatenated`() = runBlocking {
        val parts = listOf("INIT", "seg0-", "seg1-", "seg2").map { it.toByteArray() }
        MockWebServer().use { s ->
            s.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/p/list.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:1.0,\ns0.m4s\n#EXTINF:1.0,\ns1.m4s\n#EXTINF:1.0,\ns2.m4s\n#EXT-X-ENDLIST\n",
                    )
                    "/p/init.mp4" -> MockResponse().setBody(Buffer().write(parts[0]))
                    "/p/s0.m4s" -> MockResponse().setBody(Buffer().write(parts[1]))
                    "/p/s1.m4s" -> MockResponse().setBody(Buffer().write(parts[2]))
                    "/p/s2.m4s" -> MockResponse().setBody(Buffer().write(parts[3]))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val dest = File(tmp, "hls.m4a")
            val progress = mutableListOf<Float>()
            StreamDownloader().download(ResolvedStream(s.url("/p/list.m3u8").toString(), fmt, 3, true, null), dest) { progress += it }
            assertEquals("INITseg0-seg1-seg2", dest.readText())
            assertEquals(1.0f, progress.last())
            assertTrue(progress.size >= 3)
        }
    }
}
