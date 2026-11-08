package fr.offlinetune.library

import android.content.Context
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

sealed interface LyricsFillState {
    data object Idle : LyricsFillState
    data class Running(val done: Int, val total: Int, val found: Int) : LyricsFillState
    data class Finished(val found: Int, val missing: Int, val failed: Int, val alreadyHad: Int) : LyricsFillState
    data class Error(val message: String) : LyricsFillState
}

/**
 * « Paroles pour toute la bibliothèque »: every track without a .lrc is looked up on LRCLIB (synced lyrics
 * preferred) and the lyrics are saved as « same name ».lrc next to the audio file — the file itself is not touched.
 */
class LyricsFiller(private val context: Context, private val library: LibraryRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lrclib = LrclibClient()
    private val files by lazy { TrackFiles(context, library) }
    private val _state = MutableStateFlow<LyricsFillState>(LyricsFillState.Idle)
    val state: StateFlow<LyricsFillState> = _state.asStateFlow()
    private var job: Job? = null

    val isRunning get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job = scope.launch {
            try {
                if (library.treeUri == null) { _state.value = LyricsFillState.Error("Choisissez d'abord le dossier de musique."); return@launch }
                val all = library.tracks.value
                val todo = all.filter { it.lrcUri == null }
                val done = AtomicInteger(); val found = AtomicInteger(); val failed = AtomicInteger()
                val pending = java.util.concurrent.ConcurrentHashMap<String, String>()
                _state.value = LyricsFillState.Running(0, todo.size, 0)
                val gate = Semaphore(2) // LRCLIB limits requests per address: two at a time, slowed down when it asks
                coroutineScope {
                    todo.map { t ->
                        async {
                            gate.withPermit {
                                try {
                                    val info = TrackInfo(
                                        title = t.title,
                                        artist = t.artist.takeIf { it != LibraryScanner.UNKNOWN_ARTIST } ?: "",
                                        album = t.album?.takeIf { it.isNotBlank() },
                                        durationSec = t.durationMs?.let { (it / 1000).toInt() }?.takeIf { it > 0 },
                                    )
                                    val lyrics = lookup(info)
                                    if (lyrics != null) {
                                        val text = LrcFormat.build(info.copy(artist = info.artist.ifBlank { t.artist }), lyrics)
                                        pending[t.uri] = files.writeLrc(t, text)
                                        found.incrementAndGet()
                                    }
                                } catch (e: Throwable) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    failed.incrementAndGet()
                                }
                                val n = done.incrementAndGet()
                                _state.value = LyricsFillState.Running(n, todo.size, found.get())
                                // Show them in the player as they come, a batch at a time.
                                if (pending.size >= 10) flush(pending)
                            }
                        }
                    }.awaitAll()
                }
                flush(pending)
                _state.value = LyricsFillState.Finished(found.get(), todo.size - found.get() - failed.get(), failed.get(), all.size - todo.size)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) { _state.value = LyricsFillState.Idle; throw e }
                _state.value = LyricsFillState.Error(e.message ?: "Erreur inattendue")
            }
        }
    }

    fun cancel() { job?.cancel() }

    /** Pause shared by all lookups after LRCLIB answered « too many requests ». */
    @Volatile private var backoffUntil = 0L

    /**
     * LRCLIB lookup that waits and tries again when the service says « too many requests » (429) or is busy,
     * so a song is never counted as « no lyrics » only because the service refused to answer.
     */
    private suspend fun lookup(info: TrackInfo): fr.offlinetune.core.model.LyricsResult? {
        var wait = 5_000L
        repeat(6) {
            val pause = backoffUntil - System.currentTimeMillis()
            if (pause > 0) kotlinx.coroutines.delay(pause)
            try {
                return lrclib.findLoose(info)
            } catch (e: fr.offlinetune.core.model.HttpError) {
                if (e.code != 429 && e.code < 500) throw e
                backoffUntil = maxOf(backoffUntil, System.currentTimeMillis() + wait)
                wait = (wait * 2).coerceAtMost(60_000L)
            } catch (e: fr.offlinetune.core.model.NetworkLost) {
                kotlinx.coroutines.delay(wait)
            }
        }
        throw IllegalStateException("LRCLIB ne répond pas")
    }

    private suspend fun flush(pending: MutableMap<String, String>) {
        val batch = HashMap(pending)
        batch.keys.forEach { pending.remove(it) }
        library.setLyrics(batch)
    }
}
