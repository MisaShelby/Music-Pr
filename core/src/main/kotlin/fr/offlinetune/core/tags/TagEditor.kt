package fr.offlinetune.core.tags

import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.ogg.OggOpusWriter
import fr.offlinetune.core.ogg.OggReader
import fr.offlinetune.core.ogg.VorbisComment
import java.io.File

/** Rewrites the tags of a file already in the library (no re-encoding). [cover]/[lyrics] null = keep existing. */
object TagEditor {
    fun write(file: File, info: TrackInfo, cover: ByteArray?, lyrics: String?) {
        val tmp = File(file.parentFile, file.name + ".edit")
        try {
            when (file.extension.lowercase()) {
                "opus", "ogg" -> {
                    val (head, packets) = OggReader.packets(file)
                    val old = OggReader.inspect(file)
                    val keepLyrics = lyrics ?: old.comments["LYRICS"]?.firstOrNull()
                    val fields = listOf(
                        "TITLE" to info.title, "ARTIST" to info.artist, "ALBUM" to (info.album ?: ""),
                        "ALBUMARTIST" to (info.albumArtist ?: ""), "DATE" to (info.year?.toString() ?: ""),
                        "TRACKNUMBER" to (info.trackNumber?.toString() ?: ""), "GENRE" to (info.genre ?: ""),
                        "LYRICS" to (keepLyrics ?: ""),
                    )
                    val pic = cover ?: old.pictureBytes
                    val mime = if (pic != null && pic.size > 3 && pic[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                    tmp.outputStream().buffered().use { OggOpusWriter.write(head, packets.asSequence(), VorbisComment(fields = fields, picture = pic, pictureMime = mime), it) }
                }
                "mp3" -> Id3Writer.rewrite(file, tmp, info, cover, lyrics)
                "m4a", "mp4", "aac" -> Mp4Tags.write(file, tmp, info, cover, lyrics)
                else -> throw Unsupported("la modification des infos des fichiers .${file.extension} (possible pour .opus, .mp3, .m4a)")
            }
            if (!file.delete() || !tmp.renameTo(file)) throw java.io.IOException("cannot replace $file")
        } finally {
            tmp.delete()
        }
    }
}
