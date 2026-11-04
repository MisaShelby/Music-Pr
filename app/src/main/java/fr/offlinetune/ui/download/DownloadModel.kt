package fr.offlinetune.ui.download

import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.lyrics.LyricsProvider
import fr.offlinetune.core.model.SearchHit
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
    val busy: String? = null,
    val error: String? = null,
)

sealed interface DlPhase {
    data object Idle : DlPhase
    data class Loading(val what: String) : DlPhase
    data class Results(val results: InputResult.Results) : DlPhase
    data class Editing(val edit: SheetEdit, val back: InputResult.Results?) : DlPhase
    data class Error(val message: String) : DlPhase
}

class DownloadModel(private val settings: SettingsStore, private val queue: DownloadQueue) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _phase = MutableStateFlow<DlPhase>(DlPhase.Idle)
    val phase: StateFlow<DlPhase> = _phase.asStateFlow()
    val input = MutableStateFlow("")
    private var job: Job? = null

    private val lrclib = LrclibClient()
    private val lyrics = LyricsProvider { info -> if (settings.current().autoLyrics) lrclib.find(info) else null }
    private val service = SheetService.create(lyrics)

    fun submit(text: String = input.value) {
        if (text.isBlank()) return
        input.value = text.trim()
        launch(DlPhase.Loading("Recherche des infos du morceau…")) {
            when (val r = service.open(text)) {
                is InputResult.Ready -> DlPhase.Editing(SheetEdit(r.sheet, r.sheet.info, includeLyrics = r.sheet.lyrics != null), null)
                is InputResult.Results ->
                    if (r.deezer.isEmpty() && r.youtube.isEmpty()) DlPhase.Error("Aucun résultat. Essayez d'autres mots ou collez un lien.")
                    else DlPhase.Results(r)
            }
        }
    }

    fun openHit(hit: SearchHit) {
        val back = (phase.value as? DlPhase.Results)?.results
        launch(DlPhase.Loading("Préparation de la fiche…")) {
            val sheet = service.openHit(hit)
            DlPhase.Editing(SheetEdit(sheet, sheet.info, includeLyrics = sheet.lyrics != null), back)
        }
    }

    fun backToResults() {
        val e = phase.value as? DlPhase.Editing ?: return
        _phase.value = e.back?.let { DlPhase.Results(it) } ?: DlPhase.Idle
    }

    fun reset() { job?.cancel(); _phase.value = DlPhase.Idle; input.value = "" }

    fun edit(change: (TrackInfo) -> TrackInfo) = updateEdit { it.copy(edited = change(it.edited)) }
    fun setIncludeLyrics(v: Boolean) = updateEdit { it.copy(includeLyrics = v) }
    fun setCover(bytes: ByteArray) = updateEdit { it.copy(sheet = it.sheet.copy(coverBytes = bytes)) }

    fun changeSource(url: String) = sheetJob("Vérification de la source…") { e ->
        e.copy(sheet = service.withSource(e.sheet, url))
    }

    fun refreshLyrics() = sheetJob("Recherche des paroles…") { e ->
        val s = service.refreshLyrics(e.sheet, e.edited)
        e.copy(sheet = s, includeLyrics = s.lyrics != null)
    }

    /** Queues the download and goes back to an empty field; progress shows in the list below. */
    fun download() {
        val e = (phase.value as? DlPhase.Editing)?.edit ?: return
        if (e.sheet.source == null) return
        queue.enqueue(DownloadRequest(e.sheet, e.edited, e.includeLyrics && e.sheet.lyrics != null))
        reset()
    }

    private fun updateEdit(change: (SheetEdit) -> SheetEdit) = _phase.update { p ->
        if (p is DlPhase.Editing) p.copy(edit = change(p.edit)) else p
    }

    private fun sheetJob(busy: String, block: suspend (SheetEdit) -> SheetEdit) {
        val start = (phase.value as? DlPhase.Editing)?.edit ?: return
        updateEdit { it.copy(busy = busy, error = null) }
        scope.launch {
            try {
                val cur = (phase.value as? DlPhase.Editing)?.edit ?: start
                val done = block(cur)
                updateEdit { done.copy(busy = null, error = null) }
            } catch (t: Throwable) {
                updateEdit { it.copy(busy = null, error = t.userMessage()) }
            }
        }
    }

    private fun launch(loading: DlPhase, block: suspend () -> DlPhase) {
        job?.cancel()
        _phase.value = loading
        job = scope.launch {
            _phase.value = try { block() } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                DlPhase.Error(t.userMessage())
            }
        }
    }
}
