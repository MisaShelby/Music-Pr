package fr.offlinetune.ui.download

import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.lyrics.LyricsProvider
import fr.offlinetune.core.link.LinkParser
import fr.offlinetune.core.link.ParsedInput
import fr.offlinetune.core.search.DownloadOption
import fr.offlinetune.core.search.FederatedSearch
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.SearchOutcome
import fr.offlinetune.core.search.SongResult
import fr.offlinetune.core.search.SongSources
import fr.offlinetune.core.search.SourceResolver
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.core.match.Matcher
import fr.offlinetune.core.search.AlbumCard
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.RecRow
import fr.offlinetune.core.search.Recommendations
import fr.offlinetune.core.search.providers.SpotifyAccount
import fr.offlinetune.core.search.providers.SpotifyPlaylist
import fr.offlinetune.library.LibTrack
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.sheet.InputResult
import fr.offlinetune.core.sheet.SheetService
import fr.offlinetune.download.DownloadQueue
import fr.offlinetune.download.DownloadRequest
import fr.offlinetune.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editable copy of a sheet shown to the user before anything is saved. */
data class SheetEdit(
    val sheet: Sheet,
    val edited: TrackInfo,
    val includeLyrics: Boolean = true,
    /** Playlists the track will be added to once downloaded. */
    val playlistUris: Set<String> = emptySet(),
    val busy: String? = null,
    val error: String? = null,
)

/** Applies a background job's result to the sheet the user is looking at now, keeping what they typed meanwhile. */
object SheetMerge {
    fun apply(current: SheetEdit?, started: SheetEdit, patch: (Sheet) -> Sheet): SheetEdit? {
        if (current == null || current.sheet.info != started.sheet.info || current.sheet.origin != started.sheet.origin) return current
        return current.copy(sheet = patch(current.sheet), busy = null, error = null)
    }
}

sealed interface DlPhase {
    data object Idle : DlPhase
    data class Loading(val what: String) : DlPhase
    /** Multi-source results; [filter] null = « Tout ». */
    data class Federated(val outcome: SearchOutcome, val filter: Provider?) : DlPhase
    /** Sources of one song; [sources] null while resolving. */
    data class Sources(val song: SongResult, val sources: SongSources?, val back: DlPhase) : DlPhase
    /** Tracks of an album or of a Spotify playlist, only displayed; touching one opens its sources. */
    data class SongList(val title: String, val subtitle: String?, val songs: List<SongResult>, val back: DlPhase) : DlPhase
    data class Editing(val edit: SheetEdit, val back: DlPhase?) : DlPhase
    data class Error(val message: String) : DlPhase
}

sealed interface ForYouState {
    data object Loading : ForYouState
    data class Ready(val rows: List<RecRow>, val spotify: List<SpotifyPlaylist>?, val spotifyError: String?) : ForYouState
}

class DownloadModel(
    private val settings: SettingsStore,
    private val queue: DownloadQueue,
    private val spotifyAccount: SpotifyAccount,
) {
    private val recommendations = Recommendations()
    private val _forYou = MutableStateFlow<ForYouState>(ForYouState.Loading)
    val forYou: StateFlow<ForYouState> = _forYou.asStateFlow()
    private var forYouKey: List<String>? = null

    /** Suggestions from the library's most present artists (+ the user's Spotify playlists when logged in). */
    fun loadForYou(tracks: List<LibTrack>, force: Boolean = false) {
        val top = tracks.filter { it.artist != fr.offlinetune.library.LibraryScanner.UNKNOWN_ARTIST }
            .groupingBy { Matcher.firstArtistOf(it.artist) }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }
        val loggedIn = keys.spotifyTokens != null
        val key = top + listOf(loggedIn.toString())
        if (!force && key == forYouKey && _forYou.value is ForYouState.Ready) return
        forYouKey = key
        _forYou.value = ForYouState.Loading
        scope.launch {
            keys = settings.current()
            val owned = tracks.map { Recommendations.key(it.artist, it.title) }.toSet()
            val rows = runCatching { recommendations.forYou(top, owned) }.getOrDefault(emptyList())
            var spErr: String? = null
            val sp = if (keys.spotifyTokens == null) null else try { spotifyAccount.playlists() } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                spErr = e.userMessage(); null
            }
            _forYou.value = ForYouState.Ready(rows, sp, spErr)
        }
    }

    private fun single(h: ProviderHit) = SongResult(h.title, h.artist, h.album, h.durationSec, h.coverUrl, mapOf(h.provider to h))

    fun openAlbum(a: AlbumCard) {
        val back = phase.value
        launch(DlPhase.Loading("Chargement de l'album…")) {
            DlPhase.SongList(a.title, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), recommendations.albumTracks(a.id).map(::single), back)
        }
    }

    fun openSpotifyPlaylist(p: SpotifyPlaylist) {
        val back = phase.value
        launch(DlPhase.Loading("Chargement de la playlist Spotify…")) {
            val songs = spotifyAccount.tracks(p.id).map(::single)
            DlPhase.SongList(p.name, "Playlist Spotify · ${songs.size} titres", songs, back)
        }
    }

    /** Song touched in suggestions or a list (not only in search results). */
    fun openAnySong(song: SongResult) {
        val back = phase.value
        job?.cancel()
        _phase.value = DlPhase.Sources(song, null, back)
        job = scope.launch {
            val r = try { resolver.resolve(song) } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                SongSources(emptyList(), song.hits.values.filter { !it.provider.downloadable }, t.userMessage())
            }
            _phase.update { if (it is DlPhase.Sources && it.song == song) it.copy(sources = r) else it }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _phase = MutableStateFlow<DlPhase>(DlPhase.Idle)
    val phase: StateFlow<DlPhase> = _phase.asStateFlow()
    val input = MutableStateFlow("")
    private var job: Job? = null

    private val lrclib = LrclibClient()
    private val lyrics = LyricsProvider { info -> if (settings.current().autoLyrics) lrclib.find(info) else null }
    private val service = SheetService.create(lyrics)
    private val newPipe = fr.offlinetune.core.meta.NewPipeSource()
    private val resolver = SourceResolver(newPipe, lyrics)
    @Volatile private var keys: AppSettings = AppSettings()
    private val federated = FederatedSearch.create({ keys.spotifyKeys }, { keys.tidalKeys }, newPipe)

    init {
        scope.launch { settings.flow.collect { keys = it } }
    }

    /** A link opens its sheet directly (v1); text searches every platform at once. */
    fun submit(text: String = input.value) {
        if (text.isBlank()) return
        input.value = text.trim()
        val isQuery = runCatching { LinkParser.parse(text) is ParsedInput.Query }.getOrDefault(false)
        if (isQuery) {
            launch(DlPhase.Loading("Recherche sur toutes les plateformes…")) {
                keys = settings.current() // keys typed just before the first search
                val o = federated.search(text.trim())
                if (o.songs.isEmpty()) DlPhase.Error("Aucun résultat. Essayez d'autres mots ou collez un lien.")
                else DlPhase.Federated(o, null)
            }
            return
        }
        launch(DlPhase.Loading("Recherche des infos du morceau…")) {
            when (val r = service.open(text)) {
                is InputResult.Ready -> DlPhase.Editing(SheetEdit(r.sheet, r.sheet.info), null)
                is InputResult.Results -> DlPhase.Error("Aucun résultat.")
            }
        }
    }

    fun setFilter(p: Provider?) = _phase.update { if (it is DlPhase.Federated) it.copy(filter = p) else it }

    fun openSong(song: SongResult) = openAnySong(song)

    fun chooseSource(option: DownloadOption) {
        val src = phase.value as? DlPhase.Sources ?: return
        launch(DlPhase.Loading("Préparation de la fiche…")) {
            val sheet = service.fromSong(src.song, option)
            DlPhase.Editing(SheetEdit(sheet, sheet.info), src)
        }
    }

    /** Back one level: sheet → sources/results, sources → results, results → empty field. */
    fun back() {
        job?.cancel(); sheetJob?.cancel()
        _phase.value = when (val p = phase.value) {
            is DlPhase.Editing -> p.back ?: DlPhase.Idle
            is DlPhase.Sources -> p.back
            is DlPhase.SongList -> p.back
            else -> DlPhase.Idle
        }
    }

    fun backToResults() = back()

    fun togglePlaylist(uri: String) = updateEdit { e ->
        e.copy(playlistUris = if (uri in e.playlistUris) e.playlistUris - uri else e.playlistUris + uri)
    }

    fun reset() { job?.cancel(); sheetJob?.cancel(); _phase.value = DlPhase.Idle; input.value = "" }

    fun edit(change: (TrackInfo) -> TrackInfo) = updateEdit { it.copy(edited = change(it.edited)) }
    fun setIncludeLyrics(v: Boolean) = updateEdit { it.copy(includeLyrics = v) }
    /** Gallery image: read and downscaled off the main thread (cloud photos may need a download). */
    fun setCoverFrom(context: android.content.Context, uri: android.net.Uri) {
        scope.launch {
            val bytes = kotlinx.coroutines.withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(DownloadQueue::toSquareJpeg) }.getOrNull()
            }
            if (bytes != null) updateEdit { it.copy(sheet = it.sheet.copy(coverBytes = bytes)) }
            else updateEdit { it.copy(error = "Impossible de lire cette image.") }
        }
    }

    fun changeSource(url: String) = sheetJob("Vérification de la source…") { e ->
        val r = service.withSource(e.sheet, url)
        val patch: (Sheet) -> Sheet = { it.copy(source = r.source, format = r.format, warnings = r.warnings) }
        patch
    }

    fun refreshLyrics() = sheetJob("Recherche des paroles…") { e ->
        val r = service.refreshLyrics(e.sheet, e.edited)
        val patch: (Sheet) -> Sheet = { it.copy(lyrics = r.lyrics) }
        patch
    }

    /** Queues the download and goes back to an empty field; progress shows in the list below. */
    fun download() {
        val e = (phase.value as? DlPhase.Editing)?.edit ?: return
        if (e.sheet.source == null) return
        queue.enqueue(DownloadRequest(e.sheet, e.edited, e.includeLyrics && e.sheet.lyrics != null, e.playlistUris.toList()))
        reset()
    }

    private fun updateEdit(change: (SheetEdit) -> SheetEdit) = _phase.update { p ->
        if (p is DlPhase.Editing) p.copy(edit = change(p.edit)) else p
    }

    private var sheetJob: Job? = null

    /** Runs [block] on the sheet as it is now; its result is merged into whatever the user has typed since. */
    private fun sheetJob(busy: String, block: suspend (SheetEdit) -> (Sheet) -> Sheet) {
        val start = (phase.value as? DlPhase.Editing)?.edit ?: return
        updateEdit { it.copy(busy = busy, error = null) }
        sheetJob?.cancel()
        sheetJob = scope.launch {
            try {
                val patch = block(start)
                _phase.update { p -> if (p is DlPhase.Editing) p.copy(edit = SheetMerge.apply(p.edit, start, patch)!!) else p }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                updateEdit { if (it.sheet.info == start.sheet.info) it.copy(busy = null, error = t.userMessage()) else it }
            }
        }
    }

    private fun launch(loading: DlPhase, block: suspend () -> DlPhase) {
        job?.cancel()
        sheetJob?.cancel()
        _phase.value = loading
        job = scope.launch {
            _phase.value = try { block() } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                DlPhase.Error(t.userMessage())
            }
        }
    }
}
