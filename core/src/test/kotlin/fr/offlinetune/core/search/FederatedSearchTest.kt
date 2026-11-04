package fr.offlinetune.core.search

import fr.offlinetune.core.model.NeedsKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FederatedSearchTest {
    private fun hit(p: Provider, t: String, d: Int = 200) = ProviderHit(p, "u:$p:$t", t, "Artiste", null, d, null, null, "")

    private class Fake(override val provider: Provider, val block: suspend () -> List<ProviderHit>) : SearchProvider {
        override suspend fun search(query: String, limit: Int) = block()
    }

    @Test fun `a failing provider does not hide the others`() = runBlocking {
        val o = FederatedSearch(listOf(
            Fake(Provider.DEEZER) { listOf(hit(Provider.DEEZER, "A")) },
            Fake(Provider.APPLE_MUSIC) { throw RuntimeException("boom") },
        )).search("q")
        assertEquals(listOf("A"), o.songs.map { it.title })
        assertEquals(ProviderStatus.Ok, o.status[Provider.DEEZER])
        assertTrue(o.status[Provider.APPLE_MUSIC] is ProviderStatus.Failed)
    }

    @Test fun `slow provider times out quickly`() = runBlocking {
        val start = System.currentTimeMillis()
        val o = FederatedSearch(listOf(
            Fake(Provider.DEEZER) { listOf(hit(Provider.DEEZER, "A")) },
            Fake(Provider.TIDAL) { delay(20_000); emptyList() },
        ), timeoutMs = 200).search("q")
        assertTrue(System.currentTimeMillis() - start < 1500)
        assertEquals(ProviderStatus.Failed("délai dépassé"), o.status[Provider.TIDAL])
        assertEquals(1, o.songs.size)
    }

    @Test fun `a provider stuck in blocking network io does not hold the others`() = runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { s ->
            s.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val start = System.currentTimeMillis()
            val o = FederatedSearch(listOf(
                Fake(Provider.DEEZER) { listOf(hit(Provider.DEEZER, "A")) },
                Fake(Provider.TIDAL) { fr.offlinetune.core.net.Http.getText(s.url("/hang").toString()); emptyList() },
            ), timeoutMs = 300).search("q")
            assertTrue(System.currentTimeMillis() - start < 2000, "${System.currentTimeMillis() - start} ms")
            assertEquals(ProviderStatus.Failed("délai dépassé"), o.status[Provider.TIDAL])
            assertEquals(1, o.songs.size)
        }
    }

    @Test fun `missing key is reported as NeedsKey`() = runBlocking {
        val o = FederatedSearch(listOf(Fake(Provider.SPOTIFY) { throw NeedsKey("Spotify") })).search("q")
        assertEquals(ProviderStatus.NeedsKey, o.status[Provider.SPOTIFY])
    }

    @Test fun `results interleave providers and filter keeps one platform`() = runBlocking {
        val o = FederatedSearch(listOf(
            Fake(Provider.DEEZER) { listOf(hit(Provider.DEEZER, "D1"), hit(Provider.DEEZER, "D2")) },
            Fake(Provider.SOUNDCLOUD) { listOf(hit(Provider.SOUNDCLOUD, "S1"), hit(Provider.SOUNDCLOUD, "D1")) },
        )).search("q")
        assertEquals(listOf("D1", "S1", "D2"), o.songs.map { it.title })
        assertEquals(listOf("D1", "S1"), FederatedSearch.filter(o, Provider.SOUNDCLOUD).map { it.title })
        assertEquals(3, FederatedSearch.filter(o, null).size)
    }
}
