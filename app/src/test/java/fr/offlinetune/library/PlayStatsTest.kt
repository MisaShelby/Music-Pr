package fr.offlinetune.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlayStatsTest {
    @Test fun `a play counts once after 30 s or near the end`() {
        val stats = PlayStats()
        val c = PlayCounter(stats) { 1000L }
        c.onItem("a")
        c.onProgress("a", positionMs = 10_000, durationMs = 200_000)
        assertEquals(0, stats.count("a"))
        c.onProgress("a", positionMs = 31_000, durationMs = 200_000)
        c.onProgress("a", positionMs = 60_000, durationMs = 200_000)
        assertEquals(1, stats.count("a"))
        c.onItem("b")
        c.onProgress("b", positionMs = 19_500, durationMs = 20_000) // short track, near its end
        assertEquals(1, stats.count("b"))
        c.onItem("a")
        c.onProgress("a", positionMs = 40_000, durationMs = 200_000)
        assertEquals(2, stats.count("a"))
    }

    @Test fun `most played and recent lists`() {
        var now = 0L
        val s = PlayStats()
        s.record("a") { ++now }; s.record("a") { ++now }; s.record("b") { ++now }; s.record("c") { ++now }
        assertEquals(listOf("a", "c"), s.mostPlayed(2)) // tie b/c: most recent first
        assertEquals(listOf("c", "b", "a"), s.recent(3))
    }

    @Test fun `json round trip`() {
        val s = PlayStats(); s.record("x") { 5L }
        assertEquals(1, PlayStats.fromJson(s.toJson()).count("x"))
    }
}
