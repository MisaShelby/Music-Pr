package fr.offlinetune.core.search.custom

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

/** The manifest file looked for in a repository. */
const val MANIFEST_FILE = "musiquepr-source.json"

class InvalidManifest(message: String) : Exception(message)

/**
 * « Source perso »: a plain description of a music API (no code is ever run).
 * Templates: `{q}` = search words, `{key}` = the user's key, `{id}` = the track's [id].
 * Paths: `$.a.b[0].c` read inside each result.
 */
data class SourceManifest(
    val name: String,
    val search: String,
    val results: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val duration: String?,
    val durationInMs: Boolean,
    val cover: String?,
    val audio: String,
    val container: Container,
    val quality: String,
    val needsKey: Boolean,
    val description: String?,
    val headers: Map<String, String>,
    /** Path to a field that is `false` for tracks the service will not deliver (they are skipped). */
    val playable: String? = null,
) {
    companion object {
        /** Reads and checks a manifest; every problem is reported in French for the user. */
        fun parse(text: String, allowHttp: Boolean = false): SourceManifest {
            val o = runCatching { parseJson(text).jsonObject }.getOrNull() ?: throw InvalidManifest("Le fichier n'est pas un JSON valide.")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            fun need(k: String) = s(k) ?: throw InvalidManifest("Champ « $k » manquant.")
            val name = need("name").take(40)
            val search = need("search")
            val audio = need("audio")
            if ("{q}" !in search) throw InvalidManifest("« search » doit contenir {q} (les mots recherchés).")
            if (!audio.startsWith("$") && "{id}" !in audio) throw InvalidManifest("« audio » doit être un chemin ($.champ) ou un lien contenant {id}.")
            fun checkLink(k: String, v: String) {
                val ok = v.startsWith("https://") || (allowHttp && v.startsWith("http://"))
                if (!ok) throw InvalidManifest("« $k » doit être un lien https://.")
            }
            checkLink("search", search)
            if (!audio.startsWith("$")) checkLink("audio", audio)
            val container = when (s("format")?.lowercase() ?: "mp3") {
                "mp3" -> Container.MP3
                "m4a", "aac", "mp4" -> Container.M4A
                else -> throw InvalidManifest("« format » doit être mp3 ou m4a.")
            }
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.filter { (k, _) -> k.lowercase() !in setOf("cookie", "host") }?.toMap() ?: emptyMap()
            return SourceManifest(
                name = name, search = search, results = s("results") ?: "$", id = s("id") ?: "$.id",
                title = need("title"), artist = need("artist"), album = s("album"), duration = s("duration"),
                durationInMs = s("durationUnit") == "ms", cover = s("cover"), audio = audio, container = container,
                quality = s("quality") ?: container.extension.uppercase(),
                needsKey = (o["key"] as? JsonPrimitive)?.contentOrNull == "true", description = s("description"),
                headers = headers, playable = s("playable"),
            )
        }
    }
}

/** Minimal JSON paths: `$`, `.field` (any characters but dots and brackets), `[index]`. */
object JsonPaths {
    fun get(root: JsonElement?, path: String): JsonElement? {
        var cur: JsonElement? = root
        val p = path.trim().removePrefix("$")
        val tokens = Regex("""\.([^.\[\]]+)|\[(\d+)]""").findAll(p)
        for (t in tokens) {
            cur = when {
                t.groupValues[1].isNotEmpty() -> (cur as? JsonObject)?.get(t.groupValues[1])
                else -> (cur as? JsonArray)?.getOrNull(t.groupValues[2].toInt())
            } ?: return null
        }
        return cur.takeIf { it !is JsonNull }
    }

    fun text(root: JsonElement?, path: String): String? =
        (get(root, path) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun list(root: JsonElement?, path: String): List<JsonElement> = (get(root, path) as? JsonArray) ?: emptyList()
}

/** Where to find the manifest from what the user pasted (GitHub repo, folder, file, or any https .json link). */
object GithubLinks {
    fun manifestUrls(input: String): List<String> {
        val u = input.trim().removeSuffix("/")
        val gh = Regex("""^https?://github\.com/([^/]+)/([^/#?]+)(?:/(tree|blob)/([^/]+)(/.*)?)?$""").find(u)
        if (gh != null) {
            val (owner, repoRaw, kind, branch, rest) = gh.destructured
            val repo = repoRaw.removeSuffix(".git")
            val ref = branch.ifEmpty { "HEAD" }
            val path = rest.trim('/')
            val file = when {
                kind == "blob" -> path
                path.isEmpty() -> MANIFEST_FILE
                else -> "$path/$MANIFEST_FILE"
            }
            return listOf("https://raw.githubusercontent.com/$owner/$repo/$ref/$file")
        }
        if (u.startsWith("https://raw.githubusercontent.com/") && !u.endsWith(".json")) return listOf("$u/$MANIFEST_FILE")
        return listOf(u)
    }

    /** Downloads and checks the manifest. */
    suspend fun fetch(input: String): Pair<String, SourceManifest> {
        val urls = manifestUrls(input)
        if (urls.any { !it.startsWith("https://") }) throw InvalidManifest("Collez un lien https:// (GitHub ou fichier .json).")
        var last: Throwable? = null
        for (url in urls) {
            try {
                val text = Http.getText(url)
                return text to SourceManifest.parse(text)
            } catch (e: InvalidManifest) {
                throw e
            } catch (e: Throwable) {
                last = e
            }
        }
        throw InvalidManifest(
            if (last is fr.offlinetune.core.model.HttpError && last.code == 404) "Aucun fichier $MANIFEST_FILE trouvé à cet endroit du dépôt."
            else "Impossible de lire le dépôt : ${last?.message ?: "erreur inconnue"}",
        )
    }
}

/** The direct audio link of a custom hit travels in [ProviderHit.url] as `custom+<ext>:<link>`. */
object CustomAudio {
    private const val PREFIX = "custom+"
    fun encode(container: Container, url: String) = "$PREFIX${container.extension}:$url"
    fun isCustom(url: String) = url.startsWith(PREFIX)
    fun decode(url: String): Pair<Container, String>? {
        if (!isCustom(url)) return null
        val ext = url.removePrefix(PREFIX).substringBefore(':')
        val link = url.removePrefix(PREFIX).substringAfter(':')
        val c = Container.entries.firstOrNull { it.extension == ext } ?: return null
        return c to link
    }
}

object CustomSearch {
    private fun fill(template: String, values: Map<String, String>): String =
        values.entries.fold(template) { acc, (k, v) -> acc.replace("{$k}", v) }

    /** Searches one custom source. A key is required only when the manifest says so. */
    suspend fun search(m: SourceManifest, key: String?, query: String, limit: Int = 15): List<ProviderHit> {
        if (m.needsKey && key.isNullOrBlank()) throw fr.offlinetune.core.model.NeedsKey(m.name)
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val url = fill(m.search, mapOf("q" to enc(query), "key" to enc(key ?: ""), "limit" to limit.toString()))
        val root = parseJson(Http.getText(url, m.headers))
        return JsonPaths.list(root, m.results).take(limit).mapNotNull { r ->
            if (m.playable != null && JsonPaths.text(r, m.playable) == "false") return@mapNotNull null
            val id = JsonPaths.text(r, m.id)
            val audio = if (m.audio.startsWith("$")) JsonPaths.text(r, m.audio)
            else id?.let { fill(m.audio, mapOf("id" to enc(it), "key" to enc(key ?: ""))) }
            if (audio == null || !(audio.startsWith("https://") || audio.startsWith("http://"))) return@mapNotNull null
            val dur = m.duration?.let { JsonPaths.text(r, it)?.toDoubleOrNull() }?.let { if (m.durationInMs) it / 1000 else it }?.toInt()
            ProviderHit(
                provider = Provider.CUSTOM,
                url = CustomAudio.encode(m.container, audio),
                title = JsonPaths.text(r, m.title) ?: return@mapNotNull null,
                artist = JsonPaths.text(r, m.artist) ?: "",
                album = m.album?.let { JsonPaths.text(r, it) },
                durationSec = dur,
                isrc = null,
                coverUrl = m.cover?.let { JsonPaths.text(r, it) },
                quality = m.quality,
                label = m.name,
            )
        }
    }
}

/** All the user's enabled « Sources perso » as one platform of the multi-source search. */
class CustomProvider(private val sources: () -> List<Pair<SourceManifest, String?>>) : fr.offlinetune.core.search.SearchProvider {
    override val provider = Provider.CUSTOM

    override suspend fun search(query: String, limit: Int): List<ProviderHit> = kotlinx.coroutines.coroutineScope {
        val list = sources()
        if (list.isEmpty()) return@coroutineScope emptyList()
        val results = list.map { (m, key) -> async { runCatching { CustomSearch.search(m, key, query, limit) } } }.map { it.await() }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) throw results.firstNotNullOf { it.exceptionOrNull() }
        // Round-robin between sources so each one shows its best results first.
        (0 until (ok.maxOfOrNull { it.size } ?: 0)).flatMap { i -> ok.mapNotNull { it.getOrNull(i) } }
    }
}

/** Format of a custom source's audio (no probing: what the manifest declares). */
fun customFormat(container: Container, quality: String) =
    fr.offlinetune.core.model.AudioFormat(container, quality.ifBlank { container.extension.uppercase() }, 0)
