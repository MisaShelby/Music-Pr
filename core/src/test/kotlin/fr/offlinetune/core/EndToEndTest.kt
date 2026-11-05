package fr.offlinetune.core

import fr.offlinetune.core.model.Container
import fr.offlinetune.core.ogg.OggReader
import fr.offlinetune.core.save.TrackSaver
import fr.offlinetune.core.sheet.InputResult
import fr.offlinetune.core.sheet.SheetService
import fr.offlinetune.core.tags.Mp4Tags
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/** Link → sheet → saved folder, for each platform, with real network calls. Output kept in core/build/e2e-output. */
@Tag("network")
class EndToEndTest {
    private val sheets = SheetService.create()
    private val saver = TrackSaver.create()
    private val root = File("build/e2e-output").apply { mkdirs() }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC",
            "https://www.deezer.com/track/3135556",
            "https://www.youtube.com/watch?v=A2VpR8HahKc",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://soundcloud.com/forss/flickermood",
        ],
    )
    fun `link to saved folder`(link: String) = runBlocking {
        val sheet = (sheets.open(link) as InputResult.Ready).sheet
        val edited = sheet.info.copy(title = sheet.info.title + " (test)")
        val audio = saver.save(sheet, edited, includeLyrics = true, root = root) {}
        val dir = audio.parentFile
        assertTrue(File(dir, "cover.jpg").exists() || File(dir, "cover.png").exists() || File(dir, "cover.webp").exists(), dir.list()!!.joinToString())
        when (sheet.format!!.container) {
            Container.OGG_OPUS -> {
                val ogg = OggReader.inspect(audio)
                assertTrue(ogg.crcOk)
                assertEquals(listOf(edited.title), ogg.comments["TITLE"])
                assertTrue(ogg.hasPicture)
                assertTrue(ogg.durationSec > 60)
            }
            Container.M4A -> assertEquals(edited.title, Mp4Tags.read(audio)["©nam"])
            Container.MP3 -> assertEquals("ID3", String(audio.readBytes(), 0, 3))
        }
        if (sheet.lyrics != null) assertTrue(File(dir, audio.nameWithoutExtension + ".lrc").exists())
    }
}
