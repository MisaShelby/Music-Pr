package fr.offlinetune.core.search.providers

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.long
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import java.io.IOException

/**
 * OAuth2 client-credentials token for official APIs, using the developer key the user created and entered
 * in the settings (Spotify, Tidal). Cached until shortly before expiry.
 */
class ClientCredentials(
    private val platform: String,
    private val tokenUrl: String,
    private val keys: () -> Pair<String, String>?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt = 0L
    private var forKeys: Pair<String, String>? = null

    suspend fun bearer(): String = mutex.withLock {
        val k = keys()?.takeIf { it.first.isNotBlank() && it.second.isNotBlank() } ?: throw NeedsKey(platform)
        val cached = token
        if (cached != null && k == forKeys && clock() < expiresAt) return@withLock cached
        val json = withContext(Dispatchers.IO) {
            val req = Http.request(tokenUrl)
                .header("Authorization", Credentials.basic(k.first.trim(), k.second.trim()))
                .post(FormBody.Builder().add("grant_type", "client_credentials").build())
                .build()
            try {
                Http.client.newCall(req).execute().use { r ->
                    if (r.code == 400 || r.code == 401 || r.code == 403) throw BadKey(platform)
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    parseJson(r.body!!.string())
                }
            } catch (e: IOException) {
                throw NetworkLost(e)
            }
        }
        val t = json.str("access_token") ?: throw BadKey(platform)
        token = t
        forKeys = k
        expiresAt = clock() + ((json.long("expires_in") ?: 3600) - 60) * 1000
        t
    }

    /** Forget the token (e.g. after a 401 from the API). */
    suspend fun reset() = mutex.withLock { token = null }
}
