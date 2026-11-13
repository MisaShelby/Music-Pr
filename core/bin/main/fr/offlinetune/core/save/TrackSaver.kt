package fr.offlinetune.core.save

import fr.offlinetune.core.download.StreamDownloader
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.meta.NewPipeSource
import fr.offlinetune.core.meta.ResolvedStream
import fr.offlinetune.core.model.Container
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.StorageError
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.ogg.OggOpusWriter
import fr.offlinetune.core.ogg.VorbisComment
import fr.offlinetune.core.ogg.WebmReader
import fr.offlinetune.core.tags.Id3Writer
import fr.offlinetune.core.tags.Mp4Defrag
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
    private val onFailure: (sourceUrl: String) -> Unit = {},
) {
    suspend fun save(sheet: Sheet, edited: TrackInfo, includeLyrics: Boolean, root: File, onProgress: (Float) -> Unit): File {
        val source = sheet.source ?: throw Unsupported("le téléchargement sans source audio choisie")
        val name = folderName(edited)
        root.mkdirs()
        val part = File(root, ".$name.part")
        part.deleteRecursively()
        if (!part.mkdirs()) throw StorageError("cannot create $part")
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
            onFailure(source.url)
            throw if (e is java.io.IOException) StorageError(e.message ?: "write failed", e) else e
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
            // Fragmented MP4 (SoundCloud HLS) → plain MP4 so players get a duration and can seek; then tags in moov.
            // If the layout is unexpected, keep the audio untouched (cover/lrc stay beside it).
            Container.M4A -> {
                val plain = if (runCatching { Mp4Defrag.isFragmented(raw) }.getOrDefault(false)) {
                    File(raw.parentFile, "plain.tmp").takeIf { runCatching { Mp4Defrag.defragment(raw, it) }.isSuccess } ?: raw
                } else raw
                runCatching { Mp4Tags.write(plain, audio, info, cover, lyrics) }.onFailure { plain.copyTo(audio, overwrite = true) }
                if (plain != raw) plain.delete()
            }
        }
    }

    companion object {
        private const val MAX_NAME = 120
        const val MAX_NAME_BYTES = 200
        private val FORBIDDEN = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

        fun create(newPipe: NewPipeSource = NewPipeSource(), downloader: StreamDownloader = StreamDownloader()) =
            TrackSaver(
                resolve = { url ->
                    // « Sources perso »: direct link declared by the source's manifest.
                    fr.offlinetune.core.search.custom.CustomAudio.decode(url)?.let { (c, link) ->
                        ResolvedStream(link, fr.offlinetune.core.search.custom.customFormat(c, ""), null, isHls = false, contentLength = null)
                    } ?: newPipe.bestStream(url)
                },
                download = { s, f, p -> downloader.download(s, f, p) },
                onFailure = { newPipe.invalidate(it) },
            )

        fun sanitize(name: String): String {
            val s = FORBIDDEN.replace(name, "_").replace(Regex("""\s+"""), " ").trim(' ', '.')
            return s.ifEmpty { "Sans titre" }
        }

        /** ≤ 120 characters and ≤ [MAX_NAME_BYTES] UTF-8 bytes (Android file systems allow 255 bytes per name). */
        fun folderName(info: TrackInfo): String {
            var s = sanitize("${info.artist} - ${info.title}")
            if (s.length > MAX_NAME) s = cutAt(s, MAX_NAME)
            while (s.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) s = cutAt(s, s.length - 1)
            return s.trim(' ', '.').ifEmpty { "Sans titre" }
        }

        private fun cutAt(s: String, n: Int): String {
            var cut = n
            if (cut > 0 && Character.isHighSurrogate(s[cut - 1])) cut--
            return s.substring(0, cut)
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
