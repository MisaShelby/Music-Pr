package fr.offlinetune.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import fr.offlinetune.core.net.LenientJson
import fr.offlinetune.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

sealed interface LibState {
    data object Idle : LibState
    data object Scanning : LibState
    data object NeedsFolder : LibState
    data class Error(val message: String) : LibState
}

/** SAF tree node backed by DocumentsContract queries (one query per folder, much faster than DocumentFile). */
class SafNode(
    private val context: Context,
    private val treeUri: Uri,
    private val docId: String,
    override val name: String,
    private val mime: String,
    override val lastModified: Long,
) : TreeNode {
    override val uri: String get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString()
    override val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR

    override fun children(): List<TreeNode> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<TreeNode>()
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += SafNode(context, treeUri, c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3))
            }
        } ?: throw SecurityException("cannot list $docId")
        return out
    }

    companion object {
        fun root(context: Context, treeUri: Uri) =
            SafNode(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), "", DocumentsContract.Document.MIME_TYPE_DIR, 0)

        /** A folder inside a granted tree, from its document Uri. */
        fun folder(context: Context, treeUri: Uri, documentUri: Uri) =
            SafNode(context, treeUri, DocumentsContract.getDocumentId(documentUri), "", DocumentsContract.Document.MIME_TYPE_DIR, 0)
    }
}

class LibraryRepository(private val context: Context, private val settings: SettingsStore) {
    private val _tracks = MutableStateFlow<List<LibTrack>>(emptyList())
    val tracks: StateFlow<List<LibTrack>> = _tracks.asStateFlow()
    private val _state = MutableStateFlow<LibState>(LibState.Idle)
    val state: StateFlow<LibState> = _state.asStateFlow()
    private val cacheFile get() = File(context.filesDir, "library.json")
    private val mutex = Mutex()
    private val scanner = LibraryScanner(::readTags)

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        if (_tracks.value.isNotEmpty()) return@withContext
        val cached = runCatching { LenientJson.decodeFromString<List<LibTrack>>(cacheFile.readText()) }.getOrNull()
        if (cached != null) _tracks.value = sorted(cached)
    }

    private fun grantedTree(uri: String?): Uri? {
        val u = uri?.let(Uri::parse) ?: return null
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
        return if (ok) u else null
    }

    suspend fun refresh() = mutex.withLock {
        val tree = grantedTree(settings.current().libraryTreeUri)
        if (tree == null) { _state.value = LibState.NeedsFolder; return@withLock }
        _state.value = LibState.Scanning
        try {
            val result = withContext(Dispatchers.IO) {
                val root = SafNode.root(context, tree)
                root.children() // fails fast if the folder is gone (SD card removed)
                scanner.scan(root, _tracks.value.associateBy { it.uri })
            }
            publish(result)
            _state.value = LibState.Idle
        } catch (e: SecurityException) {
            _state.value = LibState.NeedsFolder
        } catch (e: Exception) {
            _state.value = LibState.Error("Impossible de lire le dossier de musique : choisissez-le à nouveau.")
        }
    }

    /** Scans only a freshly downloaded folder so it appears at once. */
    suspend fun addFolder(treeUri: Uri, folderDocumentUri: Uri) = mutex.withLock {
        val added = withContext(Dispatchers.IO) {
            runCatching { scanner.scan(SafNode.folder(context, treeUri, folderDocumentUri), emptyMap()) }.getOrDefault(emptyList())
        }
        if (added.isEmpty()) return@withLock
        val ids = added.map { it.uri }.toSet()
        publish(_tracks.value.filterNot { it.uri in ids } + added)
    }

    suspend fun readText(uri: String): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    private suspend fun publish(list: List<LibTrack>) {
        _tracks.value = sorted(list)
        withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(LenientJson.encodeToString(_tracks.value)) } }
    }

    private fun sorted(list: List<LibTrack>) = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    private fun readTags(uri: String): TagData? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(uri))
            fun k(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            TagData(
                title = k(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                year = (k(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: k(MediaMetadataRetriever.METADATA_KEY_DATE))?.take(4)?.toIntOrNull(),
                trackNumber = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull(),
                durationMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
