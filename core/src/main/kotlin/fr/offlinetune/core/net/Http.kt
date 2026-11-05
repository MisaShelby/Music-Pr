package fr.offlinetune.core.net

import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.model.NotFound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    /** Error with the server's own message ({"error":{"message":…}}, {"error_description":…}, or short text). */
    fun httpError(r: Response): fr.offlinetune.core.model.HttpError {
        val body = runCatching { r.peekBody(4096).string() }.getOrNull()
        val json = body?.let { runCatching { parseJson(it) }.getOrNull() }
        val msg = json?.str("error", "message") ?: json?.str("error_description") ?: json?.str("message") ?: json?.str("error")
            ?: body?.takeIf { it.isNotBlank() && it.length < 200 && !it.trimStart().startsWith("<") }
        return fr.offlinetune.core.model.HttpError(r.code, r.request.url.host, msg?.trim())
    }

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(MemoryCookieJar)
        .build()

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request.Builder =
        Request.Builder().url(url).header("User-Agent", USER_AGENT).apply { headers.forEach { (k, v) -> header(k, v) } }

    private inline fun <T> call(url: String, headers: Map<String, String>, read: (Response) -> T): T {
        val resp = try {
            client.newCall(request(url, headers).build()).execute()
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
        resp.use {
            if (it.code == 404) throw NotFound(url)
            if (!it.isSuccessful) throw httpError(it)
            return try { read(it) } catch (e: IOException) { throw NetworkLost(e) }
        }
    }

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { call(url, headers) { it.body!!.string() } }

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) { call(url, headers) { it.body!!.bytes() } }

    /** Follows HTTP redirects and returns the final URL. */
    suspend fun resolveRedirect(url: String): String = withContext(Dispatchers.IO) {
        val resp = try {
            client.newCall(request(url).build()).execute()
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
        resp.use { it.request.url.toString() }
    }
}

/** In-memory cookie store: needed for Google's anti-abuse exemption cookie (otherwise redirect loop). */
object MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { this.cookies["${it.domain}|${it.path}|${it.name}"] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        this.cookies.values.removeAll { it.expiresAt < now }
        return this.cookies.values.filter { it.matches(url) }
    }
}
