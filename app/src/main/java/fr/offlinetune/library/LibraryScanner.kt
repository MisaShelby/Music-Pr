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
    /** Folder names + file name from the music root (for playlist relative paths). */
    val pathSegments: List<String> = emptyList(),
    /** Cover file in the folder or picture in the file; null = not checked yet (cache from an older version). */
    val hasArtwork: Boolean? = null,
)

/** A .m3u8 / .m3u file found in the music folder. [dirSegments] = its folder from the music root. */
@Serializable
data class PlaylistFile(val uri: String, val name: String, val dirSegments: List<String>)

data class ScanResult(val tracks: List<LibTrack>, val playlists: List<PlaylistFile>)

data class TagData(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val trackNumber: Int?,
    val durationMs: Long?,
    val hasEmbeddedPicture: Boolean = false,
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

    fun scan(root: TreeNode, previous: Map<String, LibTrack>): List<LibTrack> = scanAll(root, previous).tracks

    /**
     * [onProgress] is called (on the scanning thread) after each track found, with the tracks found so far:
     * lets the library show songs while a long first scan is still reading tags.
     */
    fun scanAll(
        root: TreeNode, previous: Map<String, LibTrack>, rootSegments: List<String> = emptyList(),
        onProgress: ((List<LibTrack>) -> Unit)? = null,
    ): ScanResult {
        val out = mutableListOf<LibTrack>()
        val lists = mutableListOf<PlaylistFile>()
        walk(root, previous, out, lists, rootSegments, onProgress)
        return ScanResult(out, lists)
    }

    private fun walk(
        dir: TreeNode, previous: Map<String, LibTrack>, out: MutableList<LibTrack>, lists: MutableList<PlaylistFile>, segs: List<String>,
        onProgress: ((List<LibTrack>) -> Unit)?,
    ) {
        val kids = try { dir.children() } catch (e: Exception) { return }
        val byName = kids.associateBy { it.name.lowercase() }
        val cover = COVER_NAMES.firstNotNullOfOrNull { byName[it] }?.uri
        for (k in kids) {
            if (k.name.startsWith(".")) continue
            if (k.isDir) { walk(k, previous, out, lists, segs + k.name, onProgress); continue }
            val ext = k.name.substringAfterLast('.', "").lowercase()
            if (ext == "m3u8" || ext == "m3u") { lists += PlaylistFile(k.uri, k.name.substringBeforeLast('.'), segs); continue }
            if (ext !in AUDIO) continue
            val base = k.name.substringBeforeLast('.')
            val lrc = byName["${base.lowercase()}.lrc"]?.uri
            val prev = previous[k.uri]
            if (prev != null && prev.lastModified == k.lastModified && (prev.hasArtwork != null || cover != null)) {
                out += prev.copy(lrcUri = lrc, coverUri = cover, pathSegments = segs + k.name, hasArtwork = cover != null || prev.hasArtwork == true)
                onProgress?.invoke(out)
                continue
            }
            out += track(k, base, lrc, cover).copy(pathSegments = segs + k.name)
            onProgress?.invoke(out)
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
            hasArtwork = cover != null || t?.hasEmbeddedPicture == true,
        )
    }

    companion object {
        val AUDIO = setOf("opus", "ogg", "mp3", "m4a", "flac", "wav", "aac")
        private val COVER_NAMES = listOf("cover.jpg", "cover.png", "cover.webp", "folder.jpg", "front.jpg")
        const val UNKNOWN_ARTIST = "Artiste inconnu"
    }
}
