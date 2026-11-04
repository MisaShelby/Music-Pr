package fr.offlinetune.playlist

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import fr.offlinetune.core.playlist.M3u8
import fr.offlinetune.core.playlist.M3uEntry
import fr.offlinetune.core.save.TrackSaver
import fr.offlinetune.library.LibTrack
import fr.offlinetune.library.PlaylistFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One line of a playlist; [track] is null when the file is no longer in the library (shown greyed, skipped). */
data class PlaylistItem(val entry: M3uEntry, val track: LibTrack?)

data class Playlist(val file: PlaylistFile, val items: List<PlaylistItem>) {
    val name get() = file.name
    val playable get() = items.mapNotNull { it.track }
}

/** Pure part: matches .m3u8 lines to library tracks through their paths from the music root. */
object PlaylistResolver {
    fun resolve(file: PlaylistFile, text: String, tracks: List<LibTrack>): Playlist {
        val byPath = tracks.associateBy { it.pathSegments.joinToString("/").lowercase() }
        val items = M3u8.parse(text).map { e ->
            val segs = M3u8.resolve(file.dirSegments, e.path)
            PlaylistItem(e, byPath[segs.joinToString("/").lowercase()])
        }
        return Playlist(file, items)
    }

    fun entryFor(file: PlaylistFile, t: LibTrack): M3uEntry =
        M3uEntry(M3u8.relative(file.dirSegments, t.pathSegments), t.durationMs?.let { (it / 1000).toInt() }, "${t.artist} - ${t.title}")
}

/**
 * .m3u8 files are the truth: every change rewrites the file in place, so Poweramp / VLC see the same lists.
 * New lists go to `<library>/Playlists/`.
 */
class PlaylistRepository(private val context: Context) {

    suspend fun read(file: PlaylistFile, tracks: List<LibTrack>): Playlist = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: ""
        PlaylistResolver.resolve(file, text, tracks)
    }

    private suspend fun write(file: PlaylistFile, entries: List<M3uEntry>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(Uri.parse(file.uri), "wt")!!.use { it.write(M3u8.write(entries).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun create(treeUri: Uri, name: String): PlaylistFile = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IllegalStateException("Dossier de musique inaccessible")
        val dir = root.findFile(DIR)?.takeIf { it.isDirectory } ?: root.createDirectory(DIR)
            ?: throw IllegalStateException("Impossible de créer le dossier Playlists")
        val clean = TrackSaver.sanitize(name)
        var fileName = "$clean.m3u8"
        var n = 2
        while (dir.findFile(fileName) != null) fileName = "$clean (${n++}).m3u8"
        val doc = dir.createFile("application/octet-stream", fileName) ?: throw IllegalStateException("Impossible de créer la liste")
        val f = PlaylistFile(doc.uri.toString(), fileName.removeSuffix(".m3u8"), listOf(DIR))
        write(f, emptyList())
        f
    }

    suspend fun add(p: Playlist, tracks: List<LibTrack>) =
        write(p.file, p.items.map { it.entry } + tracks.map { PlaylistResolver.entryFor(p.file, it) })

    suspend fun remove(p: Playlist, index: Int) = write(p.file, p.items.map { it.entry }.filterIndexed { i, _ -> i != index })

    suspend fun move(p: Playlist, from: Int, to: Int) {
        val list = p.items.map { it.entry }.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        write(p.file, list)
    }

    /** Returns the new file reference (its Uri changes with the name). */
    suspend fun rename(p: Playlist, newName: String): PlaylistFile = withContext(Dispatchers.IO) {
        val ext = if (p.file.uri.endsWith(".m3u", ignoreCase = true)) "m3u" else "m3u8"
        val uri = DocumentsContract.renameDocument(context.contentResolver, Uri.parse(p.file.uri), "${TrackSaver.sanitize(newName)}.$ext")
            ?: throw IllegalStateException("Renommage impossible")
        p.file.copy(uri = uri.toString(), name = TrackSaver.sanitize(newName))
    }

    suspend fun delete(p: Playlist) = withContext(Dispatchers.IO) {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(p.file.uri))
    }

    companion object {
        const val DIR = "Playlists"
    }
}
