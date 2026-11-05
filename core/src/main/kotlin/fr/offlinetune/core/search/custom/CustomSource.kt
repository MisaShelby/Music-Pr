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
 * « Source perso »: a plain description of a music API (no code is ever run). Only a search address is needed;
 * everything else is optional and found automatically in the API's answers when not given.
 * Templates: `{q}` = search words, `{key}` = the user's key, `{id}` = the track's id. Paths: `$.a.b[0].c`.
 */
data class SourceManifest(
    val name: String,
    val search: String,
    val results: String?,
    val id: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: String?,
    val durationInMs: Boolean,
    val cover: String?,
    val audio: String?,
    val container: Container,
    val quality: String,
    val needsKey: Boolean,
    val description: String?,
    val headers: Map<String, String>,
    /** Path to a field that is `false` for tracks the service will not deliver (they are skipped). */
    val playable: String? = null,
) {
    companion object {
        private val SEARCH_KEYS = listOf("search", "searchUrl", "search_url", "searchURL", "searchEndpoint", "search_endpoint", "endpoint", "api", "url")
        private val PLACEHOLDERS = listOf("{{q}}", "{{query}}", "{query}", "{search}", "{term}", "{keyword}", "{keywords}", "{text}", "%s")

        /** Reads a manifest leniently; [fallbackName] (the repository's name) is used when the file has none. */
        fun parse(text: String, allowHttp: Boolean = true, fallbackName: String? = null): SourceManifest {
            val root = runCatching { parseJson(text) }.getOrNull() ?: throw InvalidManifest("Le fichier n'est pas un JSON valide.")
            if (isExtensionCatalogue(root)) throw InvalidManifest(
                "Ce lien est un catalogue d'extensions à télécharger et exécuter (comme Mornye), pas une description d'API : il ne peut pas être ajouté. " +
                    "Il faut un fichier qui donne une adresse de recherche et des liens audio directs.",
            )
            val o = root as? JsonObject ?: throw InvalidManifest("Ce fichier ne contient pas d'adresse de recherche.")
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val rawSearch = SEARCH_KEYS.firstNotNullOfOrNull { s(it)?.takeIf(::looksLikeLink) } ?: findSearchLink(o)
                ?: throw InvalidManifest("Ce fichier ne contient pas d'adresse de recherche (un lien https avec {q} à la place des mots recherchés).")
            val search = normaliseSearch(rawSearch)
            if (!(search.startsWith("https://") || (allowHttp && search.startsWith("http://")))) throw InvalidManifest("L'adresse de recherche doit être un lien https://.")
            val container = when (s("format")?.lowercase()) {
                "m4a", "aac", "mp4" -> Container.M4A
                else -> Container.MP3
            }
            val headers = (o["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
            }?.filter { (k, _) -> k.lowercase() !in setOf("cookie", "host") }?.toMap() ?: emptyMap()
            val keyFlag = (o["key"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            val name = (s("name") ?: s("display_name") ?: fallbackName ?: hostOf(search)).take(40)
            return SourceManifest(
                name = name, search = search, results = s("results")?.takeIf { it.startsWith("$") },
                id = s("id")?.takeIf { it.startsWith("$") }, title = s("title")?.takeIf { it.startsWith("$") },
                artist = s("artist")?.takeIf { it.startsWith("$") }, album = s("album")?.takeIf { it.startsWith("$") },
                duration = s("duration")?.takeIf { it.startsWith("$") }, durationInMs = s("durationUnit") == "ms",
                cover = s("cover")?.takeIf { it.startsWith("$") }, audio = s("audio"), container = container,
                quality = s("quality") ?: container.extension.uppercase(),
                needsKey = keyFlag == "true" || "{key}" in search, description = s("description"),
                headers = headers, playable = s("playable"),
            )
        }

        private fun looksLikeLink(v: String) = v.startsWith("https://") || v.startsWith("http://")

        /** Any link in the file that has a place for the search words. */
        private fun findSearchLink(e: JsonElement): String? = when (e) {
            is JsonPrimitive -> e.contentOrNull?.takeIf { looksLikeLink(it) && ("{q}" in it || PLACEHOLDERS.any { p -> p in it }) }
            is JsonObject -> e.values.firstNotNullOfOrNull { findSearchLink(it) }
            is JsonArray -> e.firstNotNullOfOrNull { findSearchLink(it) }
        }

        private fun normaliseSearch(raw: String): String {
            var s = PLACEHOLDERS.fold(raw) { acc, p -> acc.replace(p, "{q}") }
            if ("{q}" !in s && s.endsWith("=")) s += "{q}"
            if ("{q}" !in s) throw InvalidManifest("L'adresse de recherche doit contenir {q} à la place des mots recherchés.")
            return s
        }

        private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("api.").removePrefix("www.")

        /** Registry of downloadable plugins (download_url + sha256…), e.g. Mornye's registry.json. */
        private fun isExtensionCatalogue(e: JsonElement): Boolean {
            fun isEntry(x: JsonElement) = x is JsonObject && "download_url" in x && ("sha256" in x || "min_app_version" in x || "version" in x)
            return when (e) {
                is JsonArray -> e.isNotEmpty() && e.all(::isEntry)
                is JsonObject -> isEntry(e) || e.values.any { v -> v is JsonArray && v.isNotEmpty() && v.all(::isEntry) }
                else -> false
            }
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

    /** « owner/repo » → « repo » (used as the source's name when the file has none). */
    fun repoName(input: String): String? =
        Regex("""github\.com/[^/]+/([^/#?]+)""").find(input)?.groupValues?.get(1)?.removeSuffix(".git")

    /** Downloads and checks the manifest. */
    suspend fun fetch(input: String): Pair<String, SourceManifest> {
        val urls = manifestUrls(input)
        if (urls.any { !it.startsWith("https://") }) throw InvalidManifest("Collez un lien https:// (GitHub ou fichier .json).")
        var last: Throwable? = null
        for (url in urls) {
            try {
                val text = Http.getText(url)
                return text to SourceManifest.parse(text, allowHttp = false, fallbackName = repoName(input))
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

    private val ID = listOf("$.id", "$.track_id", "$.trackId", "$.uuid", "$.key")
    private val TITLE = listOf("$.title", "$.name", "$.track_name", "$.trackName", "$.song", "$.track.title")
    private val ARTIST = listOf("$.artist.name", "$.artist", "$.artist_name", "$.artistName", "$.user.name", "$.author", "$.uploader", "$.creator", "$.artists[0].name", "$.channel")
    private val ALBUM = listOf("$.album.title", "$.album.name", "$.album", "$.album_name", "$.albumName")
    private val DURATION = listOf("$.duration", "$.length", "$.duration_sec", "$.durationSec")
    private val COVER = listOf("$.artwork.480x480", "$.artwork.1000x1000", "$.artwork", "$.cover", "$.image", "$.thumbnail", "$.picture", "$.album.cover", "$.album_image", "$.artwork_url", "$.cover_url", "$.image_url")
    private val AUDIO = listOf("$.audio", "$.audiodownload", "$.stream_url", "$.streamUrl", "$.stream", "$.download_url", "$.downloadUrl", "$.mp3", "$.file", "$.url", "$.link")

    private fun first(r: JsonElement, given: String?, auto: List<String>): String? =
        given?.let { JsonPaths.text(r, it) } ?: auto.firstNotNullOfOrNull { p -> JsonPaths.text(r, p) }

    private fun isLink(v: String) = v.startsWith("https://") || v.startsWith("http://")

    /** The list of results: the described path, else the first list of objects found in the answer. */
    private fun resultsOf(root: JsonElement, path: String?): List<JsonElement> {
        path?.let { return JsonPaths.list(root, it) }
        fun find(e: JsonElement): JsonArray? = when (e) {
            is JsonArray -> e.takeIf { a -> a.any { it is JsonObject } } ?: e.firstNotNullOfOrNull { find(it) }
            is JsonObject -> e.values.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        return find(root) ?: emptyList()
    }

    /** Searches one custom source. A key is required only when the source needs one. */
    suspend fun search(m: SourceManifest, key: String?, query: String, limit: Int = 15): List<ProviderHit> {
        if (m.needsKey && key.isNullOrBlank()) throw fr.offlinetune.core.model.NeedsKey(m.name)
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
        val url = fill(m.search, mapOf("q" to enc(query), "key" to enc(key ?: ""), "limit" to limit.toString()))
        val root = parseJson(Http.getText(url, m.headers))
        return resultsOf(root, m.results).take(limit).mapNotNull { r ->
            if (m.playable != null && JsonPaths.text(r, m.playable) == "false") return@mapNotNull null
            val id = first(r, m.id, ID)
            val audio = when {
                m.audio == null -> AUDIO.firstNotNullOfOrNull { p -> JsonPaths.text(r, p)?.takeIf(::isLink) }
                m.audio.startsWith("$") -> JsonPaths.text(r, m.audio)
                else -> id?.let { fill(m.audio, mapOf("id" to enc(it), "key" to enc(key ?: ""))) }
            }
            if (audio == null || !isLink(audio)) return@mapNotNull null
            val path = audio.substringBefore('?').lowercase()
            val container = if (path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".aac")) Container.M4A else m.container
            val ms = m.durationInMs || (m.duration == null && JsonPaths.text(r, "$.duration") == null && JsonPaths.text(r, "$.duration_ms") != null)
            val durRaw = (first(r, m.duration, DURATION) ?: JsonPaths.text(r, "$.duration_ms"))?.toDoubleOrNull()
            val dur = durRaw?.let { if (ms) it / 1000 else it }?.toInt()
            ProviderHit(
                provider = Provider.CUSTOM,
                url = CustomAudio.encode(container, audio),
                title = first(r, m.title, TITLE) ?: return@mapNotNull null,
                artist = first(r, m.artist, ARTIST) ?: "",
                album = first(r, m.album, ALBUM),
                durationSec = dur,
                isrc = null,
                coverUrl = first(r, m.cover, COVER)?.takeIf(::isLink),
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
