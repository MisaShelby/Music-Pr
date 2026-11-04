package fr.offlinetune.core.search

import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.net.Http
import fr.offlinetune.core.net.arr
import fr.offlinetune.core.net.int
import fr.offlinetune.core.net.parseJson
import fr.offlinetune.core.net.str
import fr.offlinetune.core.search.providers.DeezerProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder

data class AlbumCard(val id: String, val title: String, val artist: String, val coverUrl: String?, val year: Int?)

sealed interface RecRow {
    val title: String
    data class Tracks(override val title: String, val hits: List<ProviderHit>) : RecRow
    data class Albums(override val title: String, val albums: List<AlbumCard>) : RecRow
}

/** « Pour vous »: suggestions from the user's own most-present artists, through the public Deezer API (no key). */
class Recommendations(private val base: String = "https://api.deezer.com") {

    private suspend fun get(path: String): JsonElement? =
        runCatching { parseJson(Http.getText("$base$path")) }.getOrNull()?.takeIf { it.str("error", "type") == null }

    private suspend fun artistId(name: String): Pair<String, String>? {
        val a = get("/search/artist?limit=1&q=${URLEncoder.encode(name, "UTF-8")}").arr("data").firstOrNull() ?: return null
        val found = a.str("name") ?: return null
        // Only keep a confident match (same artist), never a random one.
        if (Matcher.normalize(found).replace(" ", "") != Matcher.normalize(name).replace(" ", "")) return null
        return (a.str("id") ?: return null) to found
    }

    private fun JsonElement.toHit(album: String? = null, cover: String? = null, artist: String? = null) = ProviderHit(
        provider = Provider.DEEZER,
        url = "https://www.deezer.com/track/${str("id")}",
        title = str("title") ?: "",
        artist = str("artist", "name") ?: artist ?: "",
        album = str("album", "title") ?: album,
        durationSec = int("duration"),
        isrc = str("isrc"),
        coverUrl = str("album", "cover_xl") ?: cover,
        quality = DeezerProvider.QUALITY,
    )

    /** Rows for each artist (in order): their popular tracks not owned, their albums, then similar artists' hits. */
    suspend fun forYou(topArtists: List<String>, owned: Set<String>): List<RecRow> = coroutineScope {
        topArtists.map { name ->
            async {
                val (id, artist) = artistId(name) ?: return@async emptyList<RecRow>()
                val top = async { get("/artist/$id/top?limit=12").arr("data").map { it.toHit() }.filter { key(it.artist, it.title) !in owned } }
                val albums = async {
                    get("/artist/$id/albums?limit=12").arr("data").mapNotNull { a ->
                        AlbumCard(a.str("id") ?: return@mapNotNull null, a.str("title") ?: "", artist, a.str("cover_xl"), a.str("release_date")?.take(4)?.toIntOrNull())
                    }
                }
                val related = async {
                    get("/artist/$id/related?limit=4").arr("data").mapNotNull { it.str("id") }.take(4).map { rid ->
                        async { get("/artist/$rid/top?limit=3").arr("data").map { it.toHit() } }
                    }.awaitAll().flatten().filter { key(it.artist, it.title) !in owned }
                }
                listOfNotNull(
                    top.await().takeIf { it.isNotEmpty() }?.let { RecRow.Tracks("Populaire chez $artist", it) },
                    albums.await().takeIf { it.isNotEmpty() }?.let { RecRow.Albums("Albums de $artist", it) },
                    related.await().takeIf { it.isNotEmpty() }?.let { RecRow.Tracks("Si vous aimez $artist", it) },
                )
            }
        }.awaitAll().flatten()
    }

    suspend fun albumTracks(albumId: String): List<ProviderHit> {
        val album = get("/album/$albumId")
        val title = album.str("title"); val cover = album.str("cover_xl"); val artist = album.str("artist", "name")
        return get("/album/$albumId/tracks?limit=100").arr("data").map { it.toHit(title, cover, artist) }
    }

    companion object {
        /** Key used to recognise a track the user already owns. */
        fun key(artist: String, title: String) = "${Matcher.normalize(Matcher.firstArtistOf(artist))}|${Matcher.normalize(title)}"
    }
}
