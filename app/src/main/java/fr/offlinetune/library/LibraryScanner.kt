package fr.offlinetune.library

import kotlinx.serialization.Serializable

@Serializable
data class LibTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val lrcUri: String?,
    val coverUri: String?,
    val lastModified: Long,
)

data class TagData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
)

/** A file or folder of the music tree (SAF DocumentFile on the phone, fakes in tests). */
interface TreeNode {
    val name: String
    val uri: String
    val isDir: Boolean
    val lastModified: Long
    fun children(): List<TreeNode>
}

/** Recursive scan: audio files, same-name .lrc, cover.jpg / folder.jpg; unreadable parts are skipped. */
class LibraryScanner(private val tags: (uri: String) -> TagData?) {

    fun scan(root: TreeNode, previous: Map<String, LibTrack>): List<LibTrack> {
        val out = mutableListOf<LibTrack>()
        walk(root, previous, out)
        return out
    }

    private fun walk(dir: TreeNode, previous: Map<String, LibTrack>, out: MutableList<LibTrack>) {
        val kids = try { dir.children() } catch (e: Exception) { return }
        val byName = kids.associateBy { it.name.lowercase() }
        val cover = COVER_NAMES.firstNotNullOfOrNull { byName[it] }?.uri
        for (k in kids) {
            if (k.name.startsWith(".")) continue
            if (k.isDir) { walk(k, previous, out); continue }
            val ext = k.name.substringAfterLast('.', "").lowercase()
            if (ext !in AUDIO) continue
            val base = k.name.substringBeforeLast('.')
            val lrc = byName["${base.lowercase()}.lrc"]?.uri
            val prev = previous[k.uri]
            if (prev != null && prev.lastModified == k.lastModified) {
                out += prev.copy(lrcUri = lrc, coverUri = cover)
                continue
            }
            out += track(k, base, lrc, cover)
        }
    }

    private fun track(file: TreeNode, base: String, lrc: String?, cover: String?): LibTrack {
        val t = runCatching { tags(file.uri) }.getOrNull()
        val parts = base.split(" - ", limit = 2)
        val nameArtist = if (parts.size == 2) parts[0].trim() else null
        val nameTitle = if (parts.size == 2) parts[1].trim() else base
        return LibTrack(
            uri = file.uri,
            title = t?.title?.takeIf { it.isNotBlank() } ?: nameTitle,
            artist = t?.artist?.takeIf { it.isNotBlank() } ?: nameArtist ?: UNKNOWN_ARTIST,
            album = t?.album?.takeIf { it.isNotBlank() },
            albumArtist = t?.albumArtist?.takeIf { it.isNotBlank() },
            year = t?.year,
            trackNumber = t?.trackNumber,
            durationMs = t?.durationMs,
            lrcUri = lrc,
            coverUri = cover,
            lastModified = file.lastModified,
        )
    }

    companion object {
        val AUDIO = setOf("opus", "ogg", "mp3", "m4a", "flac", "wav", "aac")
        private val COVER_NAMES = listOf("cover.jpg", "cover.png", "cover.webp", "folder.jpg", "front.jpg")
        const val UNKNOWN_ARTIST = "Artiste inconnu"
    }
}
