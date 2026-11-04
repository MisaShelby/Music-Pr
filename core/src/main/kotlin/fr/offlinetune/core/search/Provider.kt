package fr.offlinetune.core.search

/** Searchable platforms. Only [downloadable] ones are ever used as audio sources. */
enum class Provider(val label: String, val initials: String, val colorHex: Long, val downloadable: Boolean) {
    YOUTUBE_MUSIC("YouTube Music", "YT", 0xFFE53935, true),
    SOUNDCLOUD("SoundCloud", "SC", 0xFFFF6A00, true),
    DEEZER("Deezer", "D", 0xFFA238FF, false),
    APPLE_MUSIC("Apple Music", "A", 0xFFFA2D48, false),
    SPOTIFY("Spotify", "S", 0xFF1DB954, false),
    TIDAL("Tidal", "T", 0xFF33383F, false),
}

/** One track as found by one platform's search. [quality] is what that platform offers ("" until resolved). */
data class ProviderHit(
    val provider: Provider,
    val url: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val isrc: String?,
    val coverUrl: String?,
    val quality: String,
    /** Shown name of the source ("YouTube" when a YouTube Music search fell back to regular YouTube). */
    val label: String = provider.label,
)

/** A song as shown in the results: the same recording found on one or more platforms. */
data class SongResult(
    val title: String,
    val artist: String,
    val album: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val hits: Map<Provider, ProviderHit>,
)

sealed interface ProviderStatus {
    data object Ok : ProviderStatus
    data object NeedsKey : ProviderStatus
    data object BadKey : ProviderStatus
    data class Failed(val message: String) : ProviderStatus
}

interface SearchProvider {
    val provider: Provider
    suspend fun search(query: String, limit: Int = 15): List<ProviderHit>
}

/** Fields of a song come from the most reliable catalogue first. */
internal val CATALOG_PRIORITY = listOf(
    Provider.DEEZER, Provider.APPLE_MUSIC, Provider.SPOTIFY, Provider.TIDAL, Provider.YOUTUBE_MUSIC, Provider.SOUNDCLOUD,
)
