package fr.offlinetune.playback

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FadesTest {
    @Test fun `crossfade gains are equal power`() {
        assertEquals(0f to 1f, Fades.crossfadeGains(0f))
        val (inEnd, outEnd) = Fades.crossfadeGains(1f)
        assertEquals(1f, inEnd, 1e-6f); assertEquals(0f, outEnd, 1e-6f)
        for (i in 0..20) {
            val (a, b) = Fades.crossfadeGains(i / 20f)
            assertEquals(1f, a * a + b * b, 1e-5f)
        }
    }

    @Test fun `gains are clamped outside 0 to 1`() {
        assertEquals(Fades.crossfadeGains(0f), Fades.crossfadeGains(-1f))
        assertEquals(Fades.crossfadeGains(1f), Fades.crossfadeGains(2f))
    }

    @Test fun `preparation starts early enough to be ready at the fade point`() {
        // 6 s fade: the outgoing player is prepared up to PREPARE_LEAD_MS before the fade window.
        assertEquals(true, Fades.shouldPrepareCrossfade(durationMs = 200_000, positionMs = 193_500, crossfadeMs = 6000))
        assertEquals(false, Fades.shouldPrepareCrossfade(durationMs = 200_000, positionMs = 190_000, crossfadeMs = 6000))
    }

    @Test fun `crossfade starts only when remaining time is within the window`() {
        assertEquals(true, Fades.shouldStartCrossfade(durationMs = 200_000, positionMs = 195_000, crossfadeMs = 6000))
        assertEquals(false, Fades.shouldStartCrossfade(durationMs = 200_000, positionMs = 150_000, crossfadeMs = 6000))
        // Track shorter than twice the fade: no crossfade.
        assertEquals(false, Fades.shouldStartCrossfade(durationMs = 10_000, positionMs = 9_000, crossfadeMs = 6000))
        assertEquals(false, Fades.shouldStartCrossfade(durationMs = -1, positionMs = 9_000, crossfadeMs = 6000))
    }
}
