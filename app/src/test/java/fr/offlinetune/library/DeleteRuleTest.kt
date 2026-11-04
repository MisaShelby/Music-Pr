package fr.offlinetune.library

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeleteRuleTest {
    private fun f(name: String) = DeleteRule.Child(name, isDir = false)
    private fun d(name: String) = DeleteRule.Child(name, isDir = true)

    @Test fun `folder with only the track, its lrc and cover can go`() {
        assertTrue(DeleteRule.folderDeletable("A - B.opus", listOf(f("A - B.opus"), f("A - B.lrc"), f("cover.jpg"))))
        assertTrue(DeleteRule.folderDeletable("A - B.opus", listOf(f("A - B.opus"))))
    }

    @Test fun `any other file or sub-folder keeps the folder`() {
        assertFalse(DeleteRule.folderDeletable("bonus.mp3", listOf(f("bonus.mp3"), d("Discovery"))))
        assertFalse(DeleteRule.folderDeletable("a.mp3", listOf(f("a.mp3"), f("b.wma"))))
        assertFalse(DeleteRule.folderDeletable("a.mp3", listOf(f("a.mp3"), f(".nomedia"))))
        assertFalse(DeleteRule.folderDeletable("a.mp3", listOf(f("a.mp3"), f("booklet.pdf"))))
        assertFalse(DeleteRule.folderDeletable("a.mp3", listOf(f("a.mp3"), f("other.lrc"))))
    }

    @Test fun `audio missing from the listing keeps the folder`() {
        assertFalse(DeleteRule.folderDeletable("a.mp3", listOf(f("cover.jpg"))))
        assertFalse(DeleteRule.folderDeletable("a.mp3", emptyList()))
    }
}
