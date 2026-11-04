package fr.offlinetune.ui.download

import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.Sheet
import fr.offlinetune.core.model.TrackInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SheetMergeTest {
    private val info = TrackInfo("Titre", "Artiste")
    private val sheet = Sheet(info, Platform.DEEZER, null, emptyList(), null, emptyList(), null, null)

    @Test fun `edits typed while a job runs are kept`() {
        val started = SheetEdit(sheet, info)
        val current = started.copy(edited = info.copy(album = "Album tapé"), busy = "Recherche…")
        val merged = SheetMerge.apply(current, started) { it.copy(lyrics = LyricsResult(null, "paroles")) }!!
        assertEquals("Album tapé", merged.edited.album)
        assertEquals("paroles", merged.sheet.lyrics!!.plain)
        assertNull(merged.busy)
    }

    @Test fun `result for another sheet is dropped`() {
        val started = SheetEdit(sheet, info)
        val other = SheetEdit(sheet.copy(info = TrackInfo("Autre", "X")), TrackInfo("Autre", "X"))
        assertSame(other, SheetMerge.apply(other, started) { it.copy(lyrics = LyricsResult(null, "p")) })
    }

    @Test fun `no current sheet stays null`() {
        assertNull(SheetMerge.apply(null, SheetEdit(sheet, info)) { it })
    }
}
