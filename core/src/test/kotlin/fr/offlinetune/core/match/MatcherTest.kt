package fr.offlinetune.core.match

import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatcherTest {
    private fun cand(title: String, artist: String, dur: Int?) = SourceCandidate("u:$title:$dur", title, artist, dur)

    @Test fun `remastered suffix still matches`() {
        val s = Matcher.score("Song", "Band", 200, cand("Song (Remastered 2011)", "Band", 200))
        assertTrue(s.score >= 0.9, "${s.score}")
        assertNull(s.rejectedReason)
    }

    @Test fun `radio edit normalizes away`() {
        assertEquals("song", Matcher.normalize("Song - Radio Edit"))
        assertEquals("song", Matcher.normalize("SONG (feat. Someone)"))
        assertEquals("ete indien", Matcher.normalize("L'Été  indien".replace("L'", "")))
    }

    @Test fun `live version ranks below studio`() {
        val ref = TrackInfo("One More Time", "Daft Punk", durationSec = 320)
        val ranked = Matcher.rank(ref, listOf(cand("One More Time (Live)", "Daft Punk", 322), cand("One More Time", "Daft Punk", 321)))
        assertEquals("One More Time", ranked.first().title)
    }

    @Test fun `duration gap over 5s is rejected`() {
        val s = Matcher.score("Song", "Band", 200, cand("Song", "Band", 207))
        assertTrue(s.rejectedReason!!.startsWith("Écart de durée"), s.rejectedReason)
    }

    @Test fun `topic channel suffix is stripped from artist`() {
        val s = Matcher.score("Around the World", "Daft Punk", null, cand("Around the World", "Daft Punk - Topic", 429))
        assertTrue(s.score >= 0.99, "${s.score}")
    }

    @Test fun `artist found in video title`() {
        val s = Matcher.score("Get Lucky", "Daft Punk", 248, cand("Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams", "Daft Punk", 248))
        assertTrue(s.score >= 0.9, "${s.score}")
    }

    @Test fun `unrelated title is rejected as weak`() {
        val s = Matcher.score("Get Lucky", "Daft Punk", 248, cand("Bohemian Rhapsody", "Queen", 248))
        assertEquals("Correspondance trop faible", s.rejectedReason)
    }

    @Test fun `feat artist in ref title does not hurt`() {
        val s = Matcher.score("Get Lucky (feat. Pharrell Williams)", "Daft Punk, Pharrell Williams", 248, cand("Get Lucky", "Daft Punk", 249))
        assertTrue(s.score >= 0.9, "${s.score}")
    }
}
