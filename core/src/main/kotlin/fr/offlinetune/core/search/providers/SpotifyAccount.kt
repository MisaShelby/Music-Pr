package fr.offlinetune.core.search.providers

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.int
import fr.offlinetune.core.net.long
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.roundToInt

/** OAuth 2 PKCE helpers (RFC 7636). */
object Pkce {
    private val ALPHABET = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')

    fun verifier(random: SecureRandom = SecureRandom()): String = String(CharArray(64) { ALPHABET[random.nextInt(ALPHABET.size)] })

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

@Serializable
data class SpotifyTokens(val access: String, val refresh: String?, val expiresAt: Long)

data class SpotifyPlaylist(val id: String, val name: String, val trackCount: Int, val coverUrl: String?)

/**
 * The user's own Spotify account through the official Web API (Authorization Code + PKCE, the user's Client ID):
 * reads their playlists to display them. Never touches Spotify audio.
 */
class SpotifyAccount(
    private val clientId: () -> String?,
    private val load: () -> SpotifyTokens?,
    private val save: suspend (SpotifyTokens?) -> Unit,
    private val accounts: String = "https://accounts.spotify.com",
    private val api: String = "https://api.spotify.com",
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    fun authorizeUrl(verifier: String, state: String): String {
        val id = clientId() ?: throw NeedsKey("Spotify")
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return "$accounts/authorize?client_id=${e(id)}&response_type=code&redirect_uri=${e(REDIRECT)}" +
            "&code_challenge_method=S256&code_challenge=${e(Pkce.challenge(verifier))}&state=${e(state)}&scope=${e(SCOPES)}"
    }

    private suspend fun token(form: FormBody): JsonElement = withContext(Dispatchers.IO) {
        val req = Http.request("$accounts/api/token").post(form).build()
        try {
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 400 || r.code == 401) throw BadKey("Spotify")
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                parseJson(r.body!!.string())
            }
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
    }

    private suspend fun store(json: JsonElement, previousRefresh: String?): SpotifyTokens {
        val t = SpotifyTokens(
            access = json.str("access_token") ?: throw BadKey("Spotify"),
            refresh = json.str("refresh_token") ?: previousRefresh,
            expiresAt = clock() + ((json.long("expires_in") ?: 3600) - 60) * 1000,
        )
        save(t)
        return t
    }

    /** Called with the code from `fr.offlinetune://spotify-callback?code=…`. */
    suspend fun exchange(code: String, verifier: String): SpotifyTokens {
        val id = clientId() ?: throw NeedsKey("Spotify")
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", REDIRECT).add("client_id", id).add("code_verifier", verifier).build()
        return store(token(form), null)
    }

    private suspend fun access(forceRefresh: Boolean = false): String = mutex.withLock {
        val t = load() ?: throw NeedsKey("Spotify (connexion au compte)")
        if (!forceRefresh && clock() < t.expiresAt) return@withLock t.access
        val refresh = t.refresh ?: throw NeedsKey("Spotify (connexion au compte)")
        val id = clientId() ?: throw NeedsKey("Spotify")
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", id).build()
        store(token(form), refresh).access
    }

    private suspend fun getJson(url: String): JsonElement? = withContext(Dispatchers.IO) {
        suspend fun call(token: String): Pair<Int, String?> = Http.client.newCall(
            Http.request(url, mapOf("Authorization" to "Bearer $token")).build(),
        ).execute().use { it.code to it.body?.string() }
        try {
            var (code, body) = call(access())
            if (code == 401) { val r = call(access(forceRefresh = true)); code = r.first; body = r.second }
            when {
                code == 404 || code == 410 -> null
                code !in 200..299 -> throw IOException("HTTP $code")
                else -> body?.let(::parseJson)
            }
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
    }

    /** All pages; null when the first page does not exist (404/410). */
    private suspend fun pagedOrNull(first: String): List<JsonElement>? {
        val out = mutableListOf<JsonElement>()
        var page = getJson(first) ?: return null
        while (true) {
            out += page.arr("items")
            val next = page.str("next") ?: break
            if (out.size >= 5000) break
            page = getJson(next) ?: break
        }
        return out
    }

    suspend fun playlists(): List<SpotifyPlaylist> = (pagedOrNull("$api/v1/me/playlists?limit=50") ?: emptyList()).mapNotNull { p ->
        SpotifyPlaylist(
            id = p.str("id") ?: return@mapNotNull null,
            name = p.str("name") ?: "",
            trackCount = p.int("tracks", "total") ?: p.int("items", "total") ?: 0,
            coverUrl = p.arr("images").firstOrNull().str("url"),
        )
    }

    /** Tracks of one playlist (`/tracks`, or the newer `/items` when the former is gone). Local files and gaps skipped. */
    suspend fun tracks(playlistId: String): List<ProviderHit> {
        val items = pagedOrNull("$api/v1/playlists/$playlistId/tracks?limit=100")
            ?: pagedOrNull("$api/v1/playlists/$playlistId/items?limit=100") ?: emptyList()
        return items.mapNotNull { it.at("track") ?: it.at("item") }.mapNotNull { t ->
            ProviderHit(
                provider = Provider.SPOTIFY,
                url = t.str("external_urls", "spotify") ?: return@mapNotNull null,
                title = t.str("name") ?: return@mapNotNull null,
                artist = t.arr("artists").mapNotNull { it.str("name") }.joinToString(", "),
                album = t.str("album", "name"),
                durationSec = t.long("duration_ms")?.let { (it / 1000.0).roundToInt() },
                isrc = t.str("external_ids", "isrc"),
                coverUrl = t.arr("album", "images").maxByOrNull { it.long("width") ?: 0 }.str("url"),
                quality = SpotifyProvider.QUALITY,
            )
        }
    }

    private fun JsonElement.at(key: String): JsonElement? =
        (this as? kotlinx.serialization.json.JsonObject)?.get(key)?.takeIf { it !is kotlinx.serialization.json.JsonNull }

    suspend fun logout() = save(null)

    companion object {
        const val REDIRECT = "fr.offlinetune://spotify-callback"
        const val SCOPES = "playlist-read-private playlist-read-collaborative"
    }
}
