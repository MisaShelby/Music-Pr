package fr.offlinetune.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import fr.offlinetune.core.model.StorageError
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.save.TrackSaver
import fr.offlinetune.core.tags.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pure rule (unit-tested): a folder may be deleted with a track only if it holds nothing else than that track's files. */
object DeleteRule {
    data class Child(val name: String, val isDir: Boolean)
    private val COVERS = setOf("cover.jpg", "cover.png", "cover.webp", "folder.jpg", "front.jpg")

    fun folderDeletable(audioName: String, children: List<Child>): Boolean {
        if (children.none { !it.isDir && it.name == audioName }) return false
        val lrc = audioName.substringBeforeLast('.') + ".lrc"
        return children.all { c -> !c.isDir && (c.name == audioName || c.name.equals(lrc, true) || c.name.lowercase() in COVERS) }
    }
}

/** Operations on tracks already on the phone (SAF): delete, share, edit tags / cover / lyrics. */
class TrackFiles(private val context: Context, private val library: LibraryRepository) {
    private val resolver get() = context.contentResolver

    private fun tree(): Uri = library.treeUri ?: throw IllegalStateException("Dossier de musique inaccessible : choisissez-le à nouveau")
    private fun docId(uri: String) = DocumentsContract.getDocumentId(Uri.parse(uri))

    /** Parent folder of a track, null when the track is directly in the music root (never deletable as a folder). */
    private fun parentDocUri(t: LibTrack): Uri? {
        val id = docId(t.uri)
        if (!id.contains('/')) return null
        val parentId = id.substringBeforeLast('/')
        if (parentId == DocumentsContract.getTreeDocumentId(tree())) return null
        return DocumentsContract.buildDocumentUriUsingTree(tree(), parentId)
    }

    private fun listChildren(folder: Uri): List<Pair<DeleteRule.Child, Uri>> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree(), DocumentsContract.getDocumentId(folder))
        val out = mutableListOf<Pair<DeleteRule.Child, Uri>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val child = DeleteRule.Child(c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                out += child to DocumentsContract.buildDocumentUriUsingTree(tree(), c.getString(0))
            }
        } ?: throw IllegalStateException("Dossier illisible")
        return out
    }

    /** True when the track's folder (not the root) contains only that track, its .lrc and a cover — checked on disk. */
    fun ownsFolder(t: LibTrack): Boolean {
        val parent = parentDocUri(t) ?: return false
        val name = t.pathSegments.lastOrNull() ?: return false
        return runCatching { DeleteRule.folderDeletable(name, listChildren(parent).map { it.first }) }.getOrDefault(false)
    }

    /** Deletes for good: the track's own folder when it holds nothing else, otherwise the audio file and its .lrc. */
    suspend fun delete(tracks: List<LibTrack>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (t in tracks) {
            val ok = runCatching {
                val parent = parentDocUri(t)
                if (parent != null && ownsFolder(t)) DocumentsContract.deleteDocument(resolver, parent)
                else {
                    t.lrcUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(it)) } }
                    DocumentsContract.deleteDocument(resolver, Uri.parse(t.uri))
                }
            }.getOrDefault(false)
            if (ok) n++
        }
        library.refresh()
        n
    }

    fun share(tracks: List<LibTrack>) {
        val uris = ArrayList(tracks.map { Uri.parse(it.uri) })
        val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        intent.type = "audio/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Partager").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeFile(target: Uri, source: File) =
        (resolver.openOutputStream(target, "wt") ?: throw StorageError("cannot open $target")).use { out -> source.inputStream().use { it.copyTo(out) } }

    private fun writeBytes(target: Uri, bytes: ByteArray) =
        (resolver.openOutputStream(target, "wt") ?: throw StorageError("cannot open $target")).use { it.write(bytes) }

    private fun createIn(parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw IllegalStateException("Impossible de créer $name")

    private fun findChild(parent: Uri, name: String): Uri? =
        runCatching { listChildren(parent).firstOrNull { it.first.name == name }?.second }.getOrNull()

    private fun rootDoc(): Uri = DocumentsContract.buildDocumentUriUsingTree(tree(), DocumentsContract.getTreeDocumentId(tree()))

    private fun supportsMove(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_MOVE) != 0
        } ?: false
    }.getOrDefault(false)

    /** Writes a new « same name ».lrc next to a track that has none; returns its address. */
    suspend fun writeLrc(t: LibTrack, text: String): String = withContext(Dispatchers.IO) {
        t.lrcUri?.let { writeBytes(Uri.parse(it), text.toByteArray(Charsets.UTF_8)); return@withContext it }
        val name = t.pathSegments.lastOrNull() ?: throw IllegalStateException("Nom de fichier inconnu")
        val target = createIn(parentDocUri(t) ?: rootDoc(), name.substringBeforeLast('.') + ".lrc")
        writeBytes(target, text.toByteArray(Charsets.UTF_8))
        target.toString()
    }

    /**
     * Saves edited info into the file (no re-encoding), the .lrc next to it, and the cover.
     * The original is backed up first and restored if writing it back fails. A track without any cover that
     * gets one and shares its folder is moved into a new « Artiste - Titre » folder when the storage allows it.
     * [lyrics]: null = unchanged, "" = remove.
     */
    suspend fun save(t: LibTrack, info: TrackInfo, cover: ByteArray?, lyrics: String?) = withContext(Dispatchers.IO) {
        val name = t.pathSegments.lastOrNull() ?: "piste.${t.uri.substringAfterLast('.')}"
        val dir = File(context.cacheDir, "edit").apply { mkdirs() }
        val backup = File(dir, "orig-$name").apply { delete() }
        val work = File(dir, name).apply { delete() }
        val audio = Uri.parse(t.uri)
        resolver.openInputStream(audio)!!.use { input -> backup.outputStream().use { input.copyTo(it) } }
        backup.copyTo(work, overwrite = true)
        TagEditor.write(work, info, cover, lyrics) // throws before anything on the phone is touched
        try {
            writeFile(audio, work)
        } catch (e: Throwable) {
            val restored = runCatching { writeFile(audio, backup) }.isSuccess
            if (!restored) {
                // Keep the only good copy where the user can reach it.
                val safe = File(context.getExternalFilesDir(null), "sauvegarde-$name")
                backup.copyTo(safe, overwrite = true)
                throw StorageError("écriture impossible ; copie d'origine gardée dans ${safe.absolutePath}", e)
            }
            throw StorageError("écriture impossible, le fichier d'origine a été remis", e)
        } finally {
            work.delete()
        }
        backup.delete()

        var lrcUri = t.lrcUri?.let(Uri::parse)
        var folder = parentDocUri(t)
        val owns = ownsFolder(t)
        var movedIntoOwnFolder = false
        if (cover != null && t.hasArtwork != true && !owns) {
            // New folder « Artiste - Titre » next to the file, audio (+ .lrc) moved into it — only if supported.
            val from = folder ?: rootDoc()
            if (supportsMove(audio)) {
                val base = TrackSaver.folderName(info)
                var dirName = base; var k = 2
                while (findChild(from, dirName) != null) dirName = "$base (${k++})"
                val newDir = DocumentsContract.createDocument(resolver, from, DocumentsContract.Document.MIME_TYPE_DIR, dirName)
                if (newDir != null) {
                    val moved = runCatching { DocumentsContract.moveDocument(resolver, audio, from, newDir) }.getOrNull()
                    if (moved != null) {
                        lrcUri = lrcUri?.let { runCatching { DocumentsContract.moveDocument(resolver, it, from, newDir) }.getOrNull() ?: it }
                        folder = newDir
                        movedIntoOwnFolder = true
                    } else {
                        runCatching { DocumentsContract.deleteDocument(resolver, newDir) }
                    }
                }
            }
        }
        // cover.jpg only in a folder that belongs to this track (never in a shared folder: it would apply to every track).
        if (cover != null && folder != null && (owns || movedIntoOwnFolder)) {
            val target = findChild(folder, "cover.jpg") ?: createIn(folder, "cover.jpg")
            writeBytes(target, cover)
        }
        if (lyrics != null) {
            val lrcName = name.substringBeforeLast('.') + ".lrc"
            if (lyrics.isEmpty()) {
                lrcUri?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            } else {
                val target = lrcUri ?: (folder ?: rootDoc()).let { f -> findChild(f, lrcName) ?: createIn(f, lrcName) }
                writeBytes(target, lyrics.toByteArray(Charsets.UTF_8))
            }
        }
        library.refresh()
    }
}
