package fr.offlinetune.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import fr.offlinetune.core.net.LenientJson
import fr.offlinetune.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private val _playlists = MutableStateFlow<List<fr.offlinetune.playlist.Playlist>>(emptyList())
    val playlists: StateFlow<List<fr.offlinetune.playlist.Playlist>> = _playlists.asStateFlow()
    private var playlistFiles: List<PlaylistFile> = emptyList()
    val playlistRepo = fr.offlinetune.playlist.PlaylistRepository(context)
    // --- play statistics (Plus écoutés / Récents / tri par lectures) ---
    private val statsFile get() = File(context.filesDir, "playstats.json")
    val stats: PlayStats by lazy { runCatching { PlayStats.fromJson(statsFile.readText()) }.getOrDefault(PlayStats()) }
    private val _statsVersion = MutableStateFlow(0)
    val statsVersion: StateFlow<Int> = _statsVersion.asStateFlow()

    /** Called by the playback service when a play is counted. */
    fun onPlayCounted() {
        runCatching { statsFile.writeText(stats.toJson()) }
        _statsVersion.value++
    }

    private val _state = MutableStateFlow<LibState>(LibState.Idle)
    val state: StateFlow<LibState> = _state.asStateFlow()
    /** Songs found so far by the running scan (shown while the library is still empty). */
    private val _found = MutableStateFlow(0)
    val found: StateFlow<Int> = _found.asStateFlow()
    private val repoScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var retryJob: kotlinx.coroutines.Job? = null
    private val cacheFile get() = File(context.filesDir, "library.json")
    private val mutex = Mutex()
    private val scanner = LibraryScanner(::readTags)

    /**
     * Reads the songs saved by the last search. Several callers run it at start (app, library screen, startup):
     * they wait for each other, so none sees an empty library while the file is still being read — that used to
     * look like « nothing known » and started a full search of the folder at every launch.
     */
    suspend fun loadCache() = cacheMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_tracks.value.isNotEmpty() || cacheLoaded) return@withContext
            val cached = runCatching { LenientJson.decodeFromString<List<LibTrack>>(cacheFile.readText()) }.getOrNull()
            if (cached != null) _tracks.value = sorted(cached)
            cacheLoaded = true
        }
    }
    private val cacheMutex = Mutex()

    @Volatile private var cacheLoaded = false

    private fun grantedTree(uri: String?): Uri? {
        val u = uri?.let(Uri::parse) ?: return null
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
        return if (ok) u else null
    }

    private var scannedOnce = false

    /** First display of the library in this app session: scan; later visits reuse the result. */
    suspend fun refreshOnce() { if (!scannedOnce) refresh() }

    /**
     * App start: show the songs saved from the last scan, without searching the folder again — the folder is
     * only searched when the user asks (« Actualiser », new folder chosen). The one exception is when nothing
     * is known yet (first use): then a first search is needed to show anything at all. It runs in the library's
     * own scope so leaving the screen does not stop it halfway.
     */
    fun startupAsync() {
        if (scannedOnce) return
        scannedOnce = true
        repoScope.launch {
            loadCache()
            if (_tracks.value.isEmpty()) refresh()
        }
    }

    /** « Actualiser »: full scan that keeps running even if the screen goes away. */
    fun refreshAsync(): kotlinx.coroutines.Job = repoScope.launch { refresh() }

    /** Shows the songs known from the last run right away (called at app start). */
    fun preloadAsync() { repoScope.launch { loadCache() } }

    /**
     * Called when the app comes back to the screen: if the last scan could not read the folder, try again
     * (instead of waiting for the user to press « Réessayer »).
     */
    fun retryIfFailed() {
        val s = _state.value
        if (scannedOnce && (s is LibState.Error || s == LibState.NeedsFolder) && !mutex.isLocked) repoScope.launch { refresh() }
    }

    /**
     * The folder could not be read (storage not ready yet after a boot, SD card waking up…): keep trying in the
     * background for a few minutes so the songs appear by themselves as soon as the folder answers.
     */
    private fun scheduleRetries() {
        if (retryJob?.isActive == true) return
        retryJob = repoScope.launch {
            for (sec in RETRY_DELAYS_SEC) {
                kotlinx.coroutines.delay(sec * 1000L)
                val s = _state.value
                if (s !is LibState.Error && s != LibState.NeedsFolder) return@launch // a scan worked meanwhile
                refresh()
            }
        }
    }

    suspend fun refresh() = mutex.withLock {
        scannedOnce = true
        // Tracks known from the last run are shown at once, even when the folder answers slowly.
        if (_tracks.value.isEmpty()) loadCache()
        val saved = settings.current().libraryTreeUri
        val tree = grantedTree(saved)
        if (tree == null) {
            _state.value = LibState.NeedsFolder
            // A folder was chosen but its permission is not visible yet (just after boot): try again later.
            if (saved != null) scheduleRetries()
            return@withLock
        }
        _state.value = LibState.Scanning
        _found.value = 0
        try {
            // Right after boot or when the app was killed, the storage provider can refuse the first queries:
            // retry before concluding that the folder is gone (the saved folder is never forgotten here).
            var attempt = 0
            val previous = _tracks.value.associateBy { it.uri }
            val showPartial = previous.isEmpty()
            var lastPublish = 0L
            val result = run {
                while (true) {
                    try {
                        return@run withContext(Dispatchers.IO) {
                            val root = SafNode.root(context, tree)
                            root.children() // fails fast if the folder is gone (SD card removed)
                            scanner.scanAll(root, previous) { soFar ->
                                _found.value = soFar.size
                                // First scan (nothing known yet): show the songs as they are found instead of
                                // a blank page until every file's tags have been read.
                                val now = System.currentTimeMillis()
                                if (showPartial && now - lastPublish > 600) {
                                    lastPublish = now
                                    _tracks.value = sorted(soFar.toList())
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (++attempt >= 4) throw e
                        kotlinx.coroutines.delay(1000L shl attempt) // 2 s, 4 s, 8 s
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            publish(result.tracks)
            playlistLock.withLock {
                // Keep lists created/renamed while the scan was running.
                val scanned = result.playlists.map { it.uri }.toSet()
                playlistFiles = result.playlists + playlistFiles.filter { it.uri !in scanned && it.dirSegments == listOf(fr.offlinetune.playlist.PlaylistRepository.DIR) }
                reloadLocked()
            }
            _state.value = LibState.Idle
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (_state.value == LibState.Scanning) _state.value = LibState.Idle
            throw e
        } catch (e: Exception) {
            // The permission is still granted (checked above), so this is the storage not answering yet:
            // say so and keep trying by ourselves rather than asking to choose the folder again.
            _state.value = LibState.Error("Le dossier de musique ne répond pas encore. Nouvel essai automatique…")
            scheduleRetries()
        }
    }

    /** Scans only a freshly downloaded folder (directly under the music root) so it appears at once. */
    suspend fun addFolder(treeUri: Uri, folderDocumentUri: Uri, folderName: String): List<LibTrack> = mutex.withLock {
        val added = withContext(Dispatchers.IO) {
            runCatching { scanner.scanAll(SafNode.folder(context, treeUri, folderDocumentUri), emptyMap(), listOf(folderName)).tracks }
                .getOrDefault(emptyList())
        }
        if (added.isEmpty()) return@withLock added
        val ids = added.map { it.uri }.toSet()
        publish(_tracks.value.filterNot { it.uri in ids } + added)
        added
    }

    // --- playlists (.m3u8 files are the truth); every read-modify-write runs under [playlistLock] ---

    private val playlistLock = Mutex()

    suspend fun reloadPlaylists() = playlistLock.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        val tracks = _tracks.value
        _playlists.value = playlistFiles.map { playlistRepo.read(it, tracks) }.sortedBy { it.name.lowercase() }
    }

    private suspend fun <T> edit(block: suspend () -> T): T = playlistLock.withLock { block().also { reloadLocked() } }

    suspend fun createPlaylist(name: String): fr.offlinetune.playlist.Playlist? {
        val tree = grantedTree(settings.current().libraryTreeUri) ?: return null
        val f = edit { playlistRepo.create(tree, name).also { playlistFiles = playlistFiles + it } }
        return _playlists.value.firstOrNull { it.file.uri == f.uri }
    }

    suspend fun addToPlaylist(p: fr.offlinetune.playlist.Playlist, tracks: List<LibTrack>) = edit { playlistRepo.add(p.file, tracks) }

    suspend fun removeFromPlaylist(p: fr.offlinetune.playlist.Playlist, index: Int) = edit {
        p.items.getOrNull(index)?.let { playlistRepo.remove(p.file, index, it.entry.path) }
    }

    suspend fun movePlaylistItem(p: fr.offlinetune.playlist.Playlist, from: Int, to: Int) = edit {
        p.items.getOrNull(from)?.let { playlistRepo.move(p.file, from, to, it.entry.path) }
    }

    /** Returns the renamed file (its Uri changes). */
    suspend fun renamePlaylist(p: fr.offlinetune.playlist.Playlist, name: String): PlaylistFile = edit {
        val nf = playlistRepo.rename(p, name)
        playlistFiles = playlistFiles.map { if (it.uri == p.file.uri) nf else it }
        nf
    }

    suspend fun deletePlaylist(p: fr.offlinetune.playlist.Playlist) = edit {
        playlistRepo.delete(p)
        playlistFiles = playlistFiles.filterNot { it.uri == p.file.uri }
    }

    /** Links freshly written .lrc files to their tracks (track uri → .lrc uri) without rescanning. */
    suspend fun setLyrics(lrcByTrack: Map<String, String>) = mutex.withLock {
        if (lrcByTrack.isEmpty()) return@withLock
        publish(_tracks.value.map { t -> lrcByTrack[t.uri]?.let { t.copy(lrcUri = it) } ?: t })
    }

    /** Tracks without any cover (no cover file in their folder, no picture in the file). */
    fun missingCover(): List<LibTrack> = _tracks.value.filter { it.hasArtwork == false }

    val treeUri: Uri? get() = grantedTree(settings.latestTree)

    suspend fun readText(uri: String): String? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    private suspend fun publish(list: List<LibTrack>) {
        _tracks.value = sorted(list)
        withContext(Dispatchers.IO) { runCatching { cacheFile.writeText(LenientJson.encodeToString(_tracks.value)) } }
    }

    private companion object {
        val RETRY_DELAYS_SEC = listOf(5L, 10L, 20L, 30L, 60L, 60L, 120L, 120L)
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
                hasEmbeddedPicture = k(MediaMetadataRetriever.METADATA_KEY_HAS_IMAGE) == "yes" || runCatching { r.embeddedPicture != null }.getOrDefault(false),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
