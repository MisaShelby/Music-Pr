package fr.offlinetune.core.model

enum class Platform(val label: String) {
    SPOTIFY("Spotify"), DEEZER("Deezer"), YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music"), SOUNDCLOUD("SoundCloud")
}

data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val genre: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val isrc: String? = null,
)

enum class Container(val extension: String) { OGG_OPUS("opus"), MP3("mp3"), M4A("m4a") }

data class AudioFormat(val container: Container, val codecLabel: String, val bitrateKbps: Int) {
    fun label() = "$codecLabel · $bitrateKbps kb/s"
}

data class SourceCandidate(
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val score: Double = 0.0,
    val rejectedReason: String? = null,
)

/** synced = LRC body lines "[mm:ss.xx]text"; plain = unsynced text. */
data class LyricsResult(val synced: String?, val plain: String?)

data class Sheet(
    val info: TrackInfo,
    val origin: Platform,
    val source: SourceCandidate?,
    val candidates: List<SourceCandidate>,
    val format: AudioFormat?,
    val warnings: List<String>,
    val lyrics: LyricsResult?,
    val coverBytes: ByteArray?,
)

data class SearchHit(
    val kind: Platform,
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val thumbUrl: String?,
)

sealed class OfflineTuneException(msg: String, cause: Throwable? = null) : Exception(msg, cause) {
    abstract fun userMessage(): String
}

class ExtractionBroken(val platform: Platform, cause: Throwable? = null) :
    OfflineTuneException("extraction broken: $platform", cause) {
    override fun userMessage() = "${platform.label} a changé, mise à jour de l'app nécessaire"
}

class NotFound(val what: String) : OfflineTuneException("not found: $what") {
    override fun userMessage() = "Introuvable : $what"
}

class NetworkLost(cause: Throwable? = null) : OfflineTuneException("network lost", cause) {
    override fun userMessage() = "Connexion perdue pendant le téléchargement"
}

/** Local write failure (full storage, folder not writable): never reported as a network problem. */
class StorageError(detail: String, cause: Throwable? = null) : OfflineTuneException("storage: $detail", cause) {
    override fun userMessage() = "Espace insuffisant ou dossier non accessible"
}

class Unsupported(val what: String) : OfflineTuneException("unsupported: $what") {
    override fun userMessage() = "Non pris en charge : $what"
}

/** French message for any throwable, never a stack trace. */
fun Throwable.userMessage(): String = when (this) {
    is OfflineTuneException -> userMessage()
    is java.io.IOException -> "Pas de connexion internet ou serveur injoignable"
    else -> "Erreur inattendue : ${this::class.simpleName}"
}
