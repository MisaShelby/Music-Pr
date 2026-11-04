package fr.offlinetune.core.recognize

import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.int
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

@Serializable
data class Recognition(
    val artist: String,
    val title: String,
    val album: String? = null,
    val songLink: String? = null,
    val deezerUrl: String? = null,
    val spotifyUrl: String? = null,
    val appleUrl: String? = null,
) {
    val query get() = "$artist $title"
}

/** Identifies a short recording (radio, TV…) through AudD's official API, with the user's token. */
class AudDClient(private val token: () -> String?, private val endpoint: String = "https://api.audd.io/") {

    suspend fun recognize(audio: File): Recognition? = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .apply { token()?.takeIf { it.isNotBlank() }?.let { addFormDataPart("api_token", it.trim()) } }
            .addFormDataPart("return", "spotify,deezer,apple_music")
            .addFormDataPart("market", "fr")
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val json = try {
            Http.client.newCall(Http.request(endpoint).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) throw Http.httpError(r)
                parseJson(r.body!!.string())
            }
        } catch (e: IOException) {
            throw NetworkLost(e)
        }
        if (json.str("status") == "error") {
            when (json.int("error", "error_code")) {
                900 -> throw BadKey("AudD")
                901 -> throw NeedsKey("AudD")
                else -> throw IOException("AudD : ${json.str("error", "error_message")}")
            }
        }
        val r = (json as? kotlinx.serialization.json.JsonObject)?.get("result") as? kotlinx.serialization.json.JsonObject ?: return@withContext null
        Recognition(
            artist = r.str("artist") ?: return@withContext null,
            title = r.str("title") ?: return@withContext null,
            album = r.str("album"),
            songLink = r.str("song_link"),
            deezerUrl = r.str("deezer", "link"),
            spotifyUrl = r.str("spotify", "external_urls", "spotify"),
            appleUrl = r.str("apple_music", "url"),
        )
    }
}
