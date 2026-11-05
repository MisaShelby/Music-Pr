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

data class QueueEntry(val mediaId: String, val title: String, val artist: String, val artworkUri: String? = null, val durationMs: Long? = null)

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
    val speed: Float = 1f,
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

    /**
     * « Aléatoire » really reorders the queue (current track first, the rest shuffled) so the queue shows
     * what comes next. [original] keeps the list order to put back when shuffle is turned off.
     */
    private var shuffled = false
    private var original: List<MediaItem>? = null

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
            shuffle = shuffled || c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = (0 until c.mediaItemCount).map { i ->
                val it = c.getMediaItemAt(i)
                QueueEntry(it.mediaId, it.mediaMetadata.title?.toString() ?: "", it.mediaMetadata.artist?.toString() ?: "",
                    it.mediaMetadata.artworkUri?.toString(), it.mediaMetadata.durationMs)
            },
            index = c.currentMediaItemIndex,
            speed = c.playbackParameters.speed,
        )
    }

    fun playList(tracks: List<PlayableTrack>, startIndex: Int) = withController { c ->
        val items = tracks.map { it.toMediaItem() }
        c.shuffleModeEnabled = false
        if (shuffled && items.size > 1) {
            original = items
            val first = items[startIndex.coerceIn(0, items.lastIndex)]
            c.setMediaItems(listOf(first) + (items - first).shuffled(), 0, 0)
        } else {
            original = null
            c.setMediaItems(items, startIndex, 0)
        }
        c.prepare()
        c.play()
    }

    fun addToQueue(track: PlayableTrack) = withController { it.addMediaItem(track.toMediaItem()) }

    /** « Lire après »: right after the current track. Starts playback when nothing is queued. */
    fun playNext(tracks: List<PlayableTrack>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        if (c.mediaItemCount == 0) { playList(tracks, 0); return@withController }
        // In shuffle mode new items land at a random place of the shuffle order: turn shuffle off so
        // « Lire après » really plays next (the rest of the queue keeps its list order).
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
    }

    /** « Ajouter à la fin » de la file d'attente. */
    fun addToEnd(tracks: List<PlayableTrack>) = withController { c ->
        if (tracks.isEmpty()) return@withController
        if (c.mediaItemCount == 0) { playList(tracks, 0); return@withController }
        c.addMediaItems(tracks.map { it.toMediaItem() })
    }

    /** « Vider la file »: keeps only the current track. */
    fun clearQueue() = withController { c ->
        val i = c.currentMediaItemIndex
        if (i < 0) { c.clearMediaItems(); return@withController }
        if (i + 1 < c.mediaItemCount) c.removeMediaItems(i + 1, c.mediaItemCount)
        if (i > 0) c.removeMediaItems(0, i)
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }
    fun togglePlay() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms); refresh() }
    fun skipTo(index: Int) = withController { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = withController { it.removeMediaItem(index) }
    fun toggleShuffle() = withController { c ->
        c.shuffleModeEnabled = false
        val cur = c.currentMediaItemIndex
        val items = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) }
        if (!shuffled) {
            shuffled = true
            if (cur < 0 || items.size < 2) { refresh(); return@withController }
            original = items
            // Keep the current track playing untouched; replace what is around it.
            val rest = items.filterIndexed { i, _ -> i != cur }.shuffled()
            if (cur + 1 < items.size) c.removeMediaItems(cur + 1, items.size)
            if (cur > 0) c.removeMediaItems(0, cur)
            c.addMediaItems(1, rest)
        } else {
            shuffled = false
            val orig = original
            original = null
            if (orig == null || cur < 0) { refresh(); return@withController }
            val now = items[cur]
            val at = orig.indexOfFirst { it.mediaId == now.mediaId }
            // Tracks added while shuffled are kept, at the end.
            val known = orig.map { it.mediaId }.toSet()
            val extra = items.filter { it.mediaId !in known }
            val stillThere = items.map { it.mediaId }.toSet()
            val before = if (at >= 0) orig.subList(0, at).filter { it.mediaId in stillThere } else emptyList()
            val after = (if (at >= 0) orig.subList(at + 1, orig.size) else orig).filter { it.mediaId in stillThere && it.mediaId != now.mediaId } + extra
            if (cur + 1 < items.size) c.removeMediaItems(cur + 1, items.size)
            if (cur > 0) c.removeMediaItems(0, cur)
            if (before.isNotEmpty()) c.addMediaItems(0, before)
            if (after.isNotEmpty()) c.addMediaItems(after)
        }
        refresh()
    }
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
