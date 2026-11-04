package fr.offlinetune.core.search

import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.model.BadKey
import fr.offlinetune.core.model.NeedsKey
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.search.providers.AppleMusicProvider
import fr.offlinetune.core.search.providers.DeezerProvider
import fr.offlinetune.core.search.providers.SoundCloudProvider
import fr.offlinetune.core.search.providers.SpotifyProvider
import fr.offlinetune.core.search.providers.TidalProvider
import fr.offlinetune.core.search.providers.YouTubeMusicProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class SearchOutcome(val songs: List<SongResult>, val status: Map<Provider, ProviderStatus>)

/** Queries every platform in parallel; a slow or failing platform never blocks the others. */
class FederatedSearch(private val providers: List<SearchProvider>, private val timeoutMs: Long = 8000) {

    suspend fun search(query: String, limit: Int = 15): SearchOutcome = supervisorScope {
        val results = providers.map { p ->
            async {
                p.provider to try {
                    withTimeout(timeoutMs) { p.search(query, limit) } to ProviderStatus.Ok
                } catch (e: TimeoutCancellationException) {
                    emptyList<ProviderHit>() to ProviderStatus.Failed("délai dépassé")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: NeedsKey) {
                    emptyList<ProviderHit>() to ProviderStatus.NeedsKey
                } catch (e: BadKey) {
                    emptyList<ProviderHit>() to ProviderStatus.BadKey
                } catch (e: Throwable) {
                    emptyList<ProviderHit>() to ProviderStatus.Failed(e.userMessage())
                }
            }
        }.awaitAll()
        // Round-robin so the first screen shows every platform's best results.
        val lists = results.map { it.second.first }
        val interleaved = (0 until (lists.maxOfOrNull { it.size } ?: 0)).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
        SearchOutcome(SongGrouper.group(interleaved), results.associate { it.first to it.second.second })
    }

    companion object {
        fun filter(outcome: SearchOutcome, provider: Provider?): List<SongResult> =
            if (provider == null) outcome.songs else outcome.songs.filter { provider in it.hits }

        /** The 6 platforms; Spotify and Tidal only work once the user has entered their own developer keys. */
        fun create(
            spotifyKeys: () -> Pair<String, String>?,
            tidalKeys: () -> Pair<String, String>?,
            newPipe: NewPipeSource = NewPipeSource(),
        ) = FederatedSearch(
            listOf(
                YouTubeMusicProvider(newPipe), SoundCloudProvider(newPipe), DeezerProvider(), AppleMusicProvider(),
                SpotifyProvider(spotifyKeys), TidalProvider(tidalKeys),
            ),
        )
    }
}
