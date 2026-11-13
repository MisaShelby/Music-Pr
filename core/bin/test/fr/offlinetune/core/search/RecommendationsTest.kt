package fr.offlinetune.core.search

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class RecommendationsTest {
    private fun track(id: Int, title: String, artist: String) =
        """{"id":$id,"title":"$title","duration":200,"artist":{"name":"$artist"},"album":{"title":"Alb","cover_xl":"c$id"}}"""

    private fun server() = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                val body = when {
                    p.startsWith("/search/artist") && "Daft" in p -> """{"data":[{"id":27,"name":"Daft Punk"}]}"""
                    p.startsWith("/search/artist") -> """{"data":[]}"""
                    p.startsWith("/artist/27/top") -> """{"data":[${track(1, "One More Time", "Daft Punk")},${track(2, "Aerodynamic", "Daft Punk")}]}"""
                    p.startsWith("/artist/27/albums") -> """{"data":[{"id":302127,"title":"Discovery","cover_xl":"disc","release_date":"2001-03-12"}]}"""
                    p.startsWith("/artist/27/related") -> """{"data":[{"id":6404,"name":"Justice"}]}"""
                    p.startsWith("/artist/6404/top") -> """{"data":[${track(3, "D.A.N.C.E.", "Justice")}]}"""
                    p.startsWith("/album/302127/tracks") -> """{"data":[{"id":1,"title":"One More Time","duration":320,"track_position":1,"artist":{"name":"Daft Punk"}}]}"""
                    p.startsWith("/album/302127") -> """{"id":302127,"title":"Discovery","cover_xl":"disc","artist":{"name":"Daft Punk"}}"""
                    else -> """{"error":{"type":"DataException","code":800}}"""
                }
                return MockResponse().setBody(body)
            }
        }
    }

    @Test fun `rows for top artists, owned tracks removed, unknown artist skipped`() = runBlocking {
        server().use { s ->
            val rec = Recommendations(s.url("").toString().trimEnd('/'))
            val rows = rec.forYou(listOf("Daft Punk", "Inconnu Total"), owned = setOf(Recommendations.key("Daft Punk", "One More Time")))
            val titles = rows.map { it.title }
            assertEquals(listOf("Populaire chez Daft Punk", "Albums de Daft Punk", "Si vous aimez Daft Punk"), titles)
            val popular = rows[0] as RecRow.Tracks
            assertEquals(listOf("Aerodynamic"), popular.hits.map { it.title })
            assertEquals(Provider.DEEZER, popular.hits[0].provider)
            val albums = rows[1] as RecRow.Albums
            assertEquals(AlbumCard("302127", "Discovery", "Daft Punk", "disc", 2001), albums.albums.single())
            assertEquals("D.A.N.C.E.", (rows[2] as RecRow.Tracks).hits.single().title)
        }
    }

    @Test fun `album tracks carry album title and cover`() = runBlocking {
        server().use { s ->
            val hits = Recommendations(s.url("").toString().trimEnd('/')).albumTracks("302127")
            val h = hits.single()
            assertEquals("Discovery", h.album); assertEquals("disc", h.coverUrl); assertEquals("https://www.deezer.com/track/1", h.url)
        }
    }

    @Tag("network")
    @Test fun `real suggestions`() = runBlocking {
        val rows = Recommendations().forYou(listOf("Daft Punk"), emptySet())
        assertTrue(rows.isNotEmpty())
    }
}
