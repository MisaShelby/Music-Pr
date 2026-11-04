package fr.offlinetune.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.save.TrackSaver
import fr.offlinetune.core.tags.TagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Operations on tracks already on the phone (SAF): delete, share, edit tags / cover / lyrics. */
class TrackFiles(private val context: Context, private val library: LibraryRepository) {
    private val resolver get() = context.contentResolver

    private fun tree(): Uri = library.treeUri ?: throw IllegalStateException("Dossier de musique inaccessible : choisissez-le à nouveau")
    private fun docId(uri: String) = DocumentsContract.getDocumentId(Uri.parse(uri))
    private fun parentDocUri(t: LibTrack): Uri? {
        val id = docId(t.uri)
        if (!id.contains('/')) return null
        return DocumentsContract.buildDocumentUriUsingTree(tree(), id.substringBeforeLast('/'))
    }

    /** True when [t] is the only track of its own folder (not the music root). */
    fun ownsFolder(t: LibTrack): Boolean {
        val dir = t.pathSegments.dropLast(1)
        if (dir.isEmpty()) return false
        return library.tracks.value.none { it.uri != t.uri && it.pathSegments.dropLast(1) == dir }
    }

    /** Deletes for good: the track's own folder (with its .lrc / cover), else the audio file and its .lrc. */
    suspend fun delete(tracks: List<LibTrack>): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (t in tracks) {
            val ok = runCatching {
                val parent = parentDocUri(t)
                if (ownsFolder(t) && parent != null) DocumentsContract.deleteDocument(resolver, parent)
                else {
                    t.lrcUri?.let { DocumentsContract.deleteDocument(resolver, Uri.parse(it)) }
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

    private fun writeBytes(target: Uri, bytes: ByteArray) =
        resolver.openOutputStream(target, "wt")!!.use { it.write(bytes) }

    private fun createIn(parent: Uri, name: String): Uri =
        DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)
            ?: throw IllegalStateException("Impossible de créer $name")

    private fun findChild(parent: Uri, name: String): Uri? {
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree(), parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree(), c.getString(0))
        }
        return null
    }

    /**
     * Saves edited info into the file (no re-encoding), the .lrc next to it, and the cover. A track without
     * cover that gets one and is not alone in its folder is moved into a new « Artiste - Titre » folder.
     */
    suspend fun save(t: LibTrack, info: TrackInfo, cover: ByteArray?, lyrics: String?) = withContext(Dispatchers.IO) {
        val name = t.pathSegments.lastOrNull() ?: "piste.${t.uri.substringAfterLast('.')}"
        val tmp = File(context.cacheDir, "edit/$name").apply { parentFile!!.mkdirs(); delete() }
        try {
            resolver.openInputStream(Uri.parse(t.uri))!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            TagEditor.write(tmp, info, cover, lyrics)
            writeBytes(Uri.parse(t.uri), tmp.readBytes())
        } finally {
            tmp.delete()
        }

        var audioUri = Uri.parse(t.uri)
        var lrcUri = t.lrcUri?.let(Uri::parse)
        var folder = parentDocUri(t) ?: DocumentsContract.buildDocumentUriUsingTree(tree(), DocumentsContract.getTreeDocumentId(tree()))

        if (cover != null && t.coverUri == null && t.hasArtwork != true && !ownsFolder(t)) {
            // New folder « Artiste - Titre » next to the file, audio (+ .lrc) moved into it.
            val base = TrackSaver.folderName(info)
            var dirName = base; var k = 2
            while (findChild(folder, dirName) != null) dirName = "$base (${k++})"
            val newDir = DocumentsContract.createDocument(resolver, folder, DocumentsContract.Document.MIME_TYPE_DIR, dirName)
                ?: throw IllegalStateException("Impossible de créer le dossier « $dirName »")
            audioUri = DocumentsContract.moveDocument(resolver, audioUri, folder, newDir) ?: audioUri
            lrcUri = lrcUri?.let { DocumentsContract.moveDocument(resolver, it, folder, newDir) ?: it }
            folder = newDir
        }
        if (cover != null && (ownsFolder(t) || t.coverUri == null)) {
            val target = t.coverUri?.takeIf { ownsFolder(t) }?.let(Uri::parse) ?: findChild(folder, "cover.jpg") ?: createIn(folder, "cover.jpg")
            writeBytes(target, cover)
        }
        if (lyrics != null) {
            val lrcName = name.substringBeforeLast('.') + ".lrc"
            val target = lrcUri ?: findChild(folder, lrcName) ?: createIn(folder, lrcName)
            writeBytes(target, lyrics.toByteArray(Charsets.UTF_8))
        }
        library.refresh()
        audioUri.toString()
    }
}
