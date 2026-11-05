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
            val abs = M3u8.absoluteSegments(e.path)
            val track = if (abs != null) {
                // Absolute path (/storage/emulated/0/Music/…): match the longest ending that is a library path.
                abs.indices.firstNotNullOfOrNull { i -> byPath[abs.drop(i).joinToString("/").lowercase()] }
            } else {
                byPath[M3u8.resolve(file.dirSegments, e.path).joinToString("/").lowercase()]
            }
            PlaylistItem(e, track)
        }
        return Playlist(file, items)
    }

    /** Null when the track's path is unknown (cache from v1 before the first scan): never write a bogus line. */
    fun entryFor(file: PlaylistFile, t: LibTrack): M3uEntry? =
        if (t.pathSegments.isEmpty()) null
        else M3uEntry(M3u8.relative(file.dirSegments, t.pathSegments), t.durationMs?.let { (it / 1000).toInt() }, "${t.artist} - ${t.title}")

    /** Index of [path] in the current file, preferring [hint] (the index the UI showed). */
    private fun locate(entries: List<M3uEntry>, hint: Int, path: String): Int? =
        if (entries.getOrNull(hint)?.path == path) hint else entries.indexOfFirst { it.path == path }.takeIf { it >= 0 }

    /** Removes the line the user saw, even if the file changed meanwhile; null when it is gone. */
    fun removeAt(entries: List<M3uEntry>, index: Int, expectedPath: String): List<M3uEntry>? {
        val i = locate(entries, index, expectedPath) ?: return null
        return entries.filterIndexed { k, _ -> k != i }
    }

    fun move(entries: List<M3uEntry>, from: Int, to: Int, expectedPath: String): List<M3uEntry>? {
        val i = locate(entries, from, expectedPath) ?: return null
        val list = entries.toMutableList()
        list.add(to.coerceIn(0, list.lastIndex), list.removeAt(i))
        return list
    }
}

/**
 * .m3u8 files are the truth: every change rewrites the file in place, so Poweramp / VLC see the same lists.
 * New lists go to `<library>/Playlists/`.
 */
class PlaylistRepository(private val context: Context) {

    suspend fun read(file: PlaylistFile, tracks: List<LibTrack>): Playlist = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { M3u8.decode(it.readBytes()).first }
        }.getOrNull() ?: ""
        PlaylistResolver.resolve(file, text, tracks)
    }

    /** .m3u8 is always UTF-8; an old .m3u keeps the encoding it was found in (often windows-1252). */
    private suspend fun write(file: PlaylistFile, entries: List<M3uEntry>) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.uri)
        val charset = if (file.uri.endsWith(".m3u", ignoreCase = true)) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { M3u8.decode(it.readBytes()).second } }.getOrNull() ?: Charsets.UTF_8
        } else Charsets.UTF_8
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Liste non modifiable")
        out.use { it.write(M3u8.write(entries).toByteArray(charset)) }
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

    /** All edits re-read the file first, so a change made meanwhile (download, other player) is kept. */
    private suspend fun current(file: PlaylistFile): List<M3uEntry> = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { M3u8.parse(M3u8.decode(it.readBytes()).first) }
            ?: throw IllegalStateException("Liste illisible")
    }

    suspend fun add(file: PlaylistFile, tracks: List<LibTrack>) {
        val cur = current(file)
        val new = tracks.mapNotNull { PlaylistResolver.entryFor(file, it) }.filter { e -> cur.none { it.path == e.path } }
        if (new.isNotEmpty()) write(file, cur + new)
    }

    suspend fun remove(file: PlaylistFile, index: Int, path: String) {
        PlaylistResolver.removeAt(current(file), index, path)?.let { write(file, it) }
    }

    suspend fun move(file: PlaylistFile, from: Int, to: Int, path: String) {
        PlaylistResolver.move(current(file), from, to, path)?.let { write(file, it) }
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
