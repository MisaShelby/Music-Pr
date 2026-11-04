package fr.offlinetune.core.save

import fr.offlinetune.core.download.StreamDownloader
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.meta.ResolvedStream
import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.ogg.OggOpusWriter
import fr.offlinetune.core.ogg.VorbisComment
import fr.offlinetune.core.ogg.WebmReader
import fr.offlinetune.core.tags.Id3Writer
import fr.offlinetune.core.tags.Mp4Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Saves one track as `<root>/<Artiste> - <Titre>/` with the tagged audio file, the `.lrc` and `cover.jpg`.
 * Work happens in a hidden `.<name>.part` folder that is renamed at the end, or deleted on failure.
 */
class TrackSaver(
    private val resolve: suspend (url: String) -> ResolvedStream,
    private val download: suspend (ResolvedStream, File, (Float) -> Unit) -> Unit,
) {
    suspend fun save(sheet: Sheet, edited: TrackInfo, includeLyrics: Boolean, root: File, onProgress: (Float) -> Unit): File {
        val source = sheet.source ?: throw Unsupported("le téléchargement sans source audio choisie")
        val name = folderName(edited)
        root.mkdirs()
        val part = File(root, ".$name.part")
        part.deleteRecursively()
        part.mkdirs()
        try {
            val stream = resolve(source.url)
            val raw = File(part, "source.tmp")
            download(stream, raw) { onProgress(it * 0.9f) }
            val lyricsText = if (includeLyrics) sheet.lyrics?.let { it.synced ?: it.plain } else null
            val audio = File(part, "$name.${stream.format.container.extension}")
            withContext(Dispatchers.IO) {
                writeTagged(stream.format.container, raw, audio, edited, sheet.coverBytes, lyricsText)
                raw.delete()
                sheet.coverBytes?.let { File(part, "cover.${imageExtension(it)}").writeBytes(it) }
                if (includeLyrics && sheet.lyrics != null) {
                    val withLength = edited.copy(durationSec = edited.durationSec ?: stream.durationSec)
                    File(part, "$name.lrc").writeText(LrcFormat.build(withLength, sheet.lyrics), Charsets.UTF_8)
                }
            }
            val final = uniqueDir(root, name)
            if (!part.renameTo(final)) throw java.io.IOException("rename failed: $final")
            onProgress(1f)
            return File(final, audio.name)
        } catch (e: Throwable) {
            part.deleteRecursively()
            throw e
        }
    }

    private fun writeTagged(container: Container, raw: File, audio: File, info: TrackInfo, cover: ByteArray?, lyrics: String?) {
        when (container) {
            Container.OGG_OPUS -> {
                val webm = raw.inputStream().buffered().use { WebmReader.read(it) }
                val fields = listOf(
                    "TITLE" to info.title, "ARTIST" to info.artist, "ALBUM" to (info.album ?: ""),
                    "ALBUMARTIST" to (info.albumArtist ?: ""), "DATE" to (info.year?.toString() ?: ""),
                    "TRACKNUMBER" to (info.trackNumber?.toString() ?: ""), "GENRE" to (info.genre ?: ""),
                    "LYRICS" to (lyrics ?: ""),
                )
                val mime = if (cover != null && imageExtension(cover) == "png") "image/png" else "image/jpeg"
                audio.outputStream().buffered().use { OggOpusWriter.write(webm.opusHead, webm.packets, VorbisComment(fields = fields, picture = cover, pictureMime = mime), it) }
            }
            Container.MP3 -> Id3Writer.rewrite(raw, audio, info, cover, lyrics)
            // Tags in the moov box; if the file layout is unexpected, keep the audio untouched (cover/lrc stay beside it).
            Container.M4A -> runCatching { Mp4Tags.write(raw, audio, info, cover, lyrics) }.onFailure { raw.copyTo(audio, overwrite = true) }
        }
    }

    companion object {
        private const val MAX_NAME = 120
        private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

        fun create(newPipe: NewPipeSource = NewPipeSource(), downloader: StreamDownloader = StreamDownloader()) =
            TrackSaver(resolve = { newPipe.bestStream(it) }, download = { s, f, p -> downloader.download(s, f, p) })

        fun sanitize(name: String): String {
            val s = FORBIDDEN.replace(name, "_").replace(Regex("""\s+"""), " ").trim(' ', '.')
            return s.ifEmpty { "Sans titre" }
        }

        fun folderName(info: TrackInfo): String {
            val full = sanitize("${info.artist} - ${info.title}")
            if (full.length <= MAX_NAME) return full
            var cut = MAX_NAME
            if (Character.isHighSurrogate(full[cut - 1])) cut--
            return full.substring(0, cut).trim(' ', '.')
        }

        fun uniqueDir(root: File, name: String): File {
            var dir = File(root, name)
            var n = 2
            while (dir.exists()) dir = File(root, "$name (${n++})")
            return dir
        }

        fun imageExtension(b: ByteArray): String = when {
            b.size > 3 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "png"
            b.size > 11 && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
            else -> "jpg"
        }
    }
}
