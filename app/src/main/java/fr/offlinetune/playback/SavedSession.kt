package fr.offlinetune.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import fr.offlinetune.core.net.LenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

/** One song of the saved queue (enough to rebuild its MediaItem). */
@Serializable
data class SavedItem(
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: String? = null,
)

/**
 * What was playing when the app was closed: the queue, the current song and where it was, the repeat mode,
 * and the « Aléatoire » state ([original] = list order to put back when shuffle is turned off).
 */
@Serializable
data class SavedSession(
    val items: List<SavedItem> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffled: Boolean = false,
    val original: List<String>? = null,
)

/** Saves / restores the playing session in the app's private files, so reopening the app finds it again. */
object SessionStore {
    private fun file(context: Context) = File(context.filesDir, "session.json")

    /** « Aléatoire » state, kept by the UI side ([PlayerConnection]); written into the next save. */
    @Volatile var shuffled: Boolean = false
    @Volatile var original: List<String>? = null

    fun load(context: Context): SavedSession? =
        runCatching { LenientJson.decodeFromString<SavedSession>(file(context).readText()) }.getOrNull()?.takeIf { it.items.isNotEmpty() }

    fun save(context: Context, s: SavedSession) {
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(LenientJson.encodeToString(s))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Snapshot of [p] (call on the player's thread). */
    fun snapshot(p: Player): SavedSession = SavedSession(
        items = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).toSaved() },
        index = p.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = p.currentPosition.coerceAtLeast(0),
        repeatMode = p.repeatMode,
        shuffled = shuffled,
        original = original,
    )

    private fun MediaItem.toSaved() = SavedItem(
        uri = localConfiguration?.uri?.toString() ?: mediaId,
        title = mediaMetadata.title?.toString(),
        artist = mediaMetadata.artist?.toString(),
        album = mediaMetadata.albumTitle?.toString(),
        artwork = mediaMetadata.artworkUri?.toString(),
    )

    fun SavedItem.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(artwork?.let { Uri.parse(it) }).build(),
        )
        .build()
}
