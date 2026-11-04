package fr.offlinetune.download

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.save.TrackSaver
import fr.offlinetune.library.LibraryRepository
import fr.offlinetune.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicLong

sealed interface DlState {
    data object Queued : DlState
    data class Running(val progress: Float) : DlState
    data class Done(val folderName: String) : DlState
    data class Failed(val message: String) : DlState
}

data class DownloadRequest(val sheet: Sheet, val edited: TrackInfo, val includeLyrics: Boolean)
data class DownloadItem(val id: Long, val label: String, val state: DlState, val request: DownloadRequest)

/** One download at a time; each finished folder is copied into the chosen SAF folder and added to the library. */
class DownloadQueue(
    private val context: Context,
    private val settings: SettingsStore,
    private val library: LibraryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()
    private val channel = Channel<Long>(Channel.UNLIMITED)
    private val ids = AtomicLong()
    private val saver = TrackSaver.create()

    val isBusy get() = _items.value.any { it.state is DlState.Queued || it.state is DlState.Running }

    init {
        scope.launch { for (id in channel) run(id) }
    }

    fun enqueue(request: DownloadRequest) {
        val id = ids.incrementAndGet()
        _items.update { listOf(DownloadItem(id, "${request.edited.artist} - ${request.edited.title}", DlState.Queued, request)) + it }
        channel.trySend(id)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun retry(id: Long) {
        set(id, DlState.Queued)
        channel.trySend(id)
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    fun clearFinished() = _items.update { list -> list.filter { it.state is DlState.Queued || it.state is DlState.Running } }

    private fun set(id: Long, state: DlState) = _items.update { list -> list.map { if (it.id == id) it.copy(state = state) else it } }

    private suspend fun run(id: Long) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        val work = File(context.cacheDir, "dl").apply { mkdirs() }
        try {
            val treeUri = settings.current().effectiveDownloadTree?.let(Uri::parse)
                ?: throw IllegalStateException("Choisissez le dossier de téléchargement dans Réglages.")
            val root = DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.canWrite() }
                ?: throw IllegalStateException("Le dossier de téléchargement n'est plus accessible : choisissez-le à nouveau dans Réglages.")
            set(id, DlState.Running(0f))
            val sheet = item.request.sheet.copy(coverBytes = item.request.sheet.coverBytes?.let(::toSquareJpeg))
            val audio = saver.save(sheet, item.request.edited, item.request.includeLyrics, work) { p -> set(id, DlState.Running(p * 0.95f)) }
            val folder = audio.parentFile!!
            val created = withContext(Dispatchers.IO) { copyToTree(folder, root) }
            folder.deleteRecursively()
            library.addFolder(treeUri, created.uri)
            set(id, DlState.Done(created.name ?: folder.name))
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            set(id, DlState.Failed(if (e is IllegalStateException) e.message ?: "Erreur" else e.userMessage()))
        }
    }

    private fun copyToTree(folder: File, root: DocumentFile): DocumentFile {
        var name = folder.name.replace(Regex(""" \(\d+\)$"""), "")
        val base = name
        var n = 2
        while (root.findFile(name) != null) name = "$base (${n++})"
        val dir = root.createDirectory(name) ?: throw IllegalStateException("Impossible de créer le dossier « $name ».")
        try {
            folder.listFiles()!!.forEach { f ->
                // octet-stream: the provider keeps our file name and extension as is.
                val doc = dir.createFile("application/octet-stream", f.name) ?: throw IllegalStateException("Impossible d'écrire ${f.name}.")
                context.contentResolver.openOutputStream(doc.uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
        } catch (e: Throwable) {
            dir.delete()
            throw e
        }
        return dir
    }

    companion object {
        /** Players expect a square cover.jpg: crop wide video thumbnails to the centre and re-encode non-JPEG images. */
        fun toSquareJpeg(bytes: ByteArray): ByteArray {
            val isJpeg = bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val wide = bounds.outHeight > 0 && bounds.outWidth.toFloat() / bounds.outHeight > 1.2f
            if (isJpeg && !wide) return bytes
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val out = if (wide) {
                val side = bmp.height
                Bitmap.createBitmap(bmp, (bmp.width - side) / 2, 0, side, side)
            } else bmp
            return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        }
    }
}
