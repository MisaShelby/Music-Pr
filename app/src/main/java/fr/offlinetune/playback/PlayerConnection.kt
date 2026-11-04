package fr.offlinetune.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QueueEntry(val mediaId: String, val title: String, val artist: String)

data class PlayerUiState(
    val connected: Boolean = false,
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
)

/** Track to play: built by the library (Task 16). */
data class PlayableTrack(val uri: String, val title: String, val artist: String, val album: String?, val coverUri: String?)

/** UI-side connection to [PlaybackService] through a MediaController. */
class PlayerConnection(private val context: Context) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            pending.forEach { it(c) }; pending.clear()
            refresh()
        }, ContextCompat.getMainExecutor(context))
        scope.launch {
            while (true) {
                if (_state.value.isPlaying) refresh()
                delay(250)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending += block; connect() }
    }

    private fun refresh() {
        val c = controller ?: return
        val md = c.mediaMetadata
        _state.value = PlayerUiState(
            connected = true,
            mediaId = c.currentMediaItem?.mediaId,
            title = md.title?.toString() ?: "",
            artist = md.artist?.toString() ?: "",
            album = md.albumTitle?.toString() ?: "",
            artworkUri = md.artworkUri,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = (0 until c.mediaItemCount).map { i ->
                val it = c.getMediaItemAt(i)
                QueueEntry(it.mediaId, it.mediaMetadata.title?.toString() ?: "", it.mediaMetadata.artist?.toString() ?: "")
            },
            index = c.currentMediaItemIndex,
        )
    }

    fun playList(tracks: List<PlayableTrack>, startIndex: Int) = withController { c ->
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0)
        c.prepare()
        c.play()
    }

    fun addToQueue(track: PlayableTrack) = withController { it.addMediaItem(track.toMediaItem()) }
    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); refresh() }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun PlayableTrack.toMediaItem() = MediaItem.Builder()
        .setUri(uri)
        .setMediaId(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
                .setArtworkUri(coverUri?.let { Uri.parse(it) }).build(),
        )
        .build()
}
