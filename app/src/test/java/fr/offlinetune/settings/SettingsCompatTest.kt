package fr.offlinetune.settings

import fr.offlinetune.core.net.LenientJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SettingsCompatTest {
    @Test fun `v1 settings decode with the new fields defaulted`() {
        val v1 = """{"libraryTreeUri":"content://x","crossfadeSec":8,"dsp":{"bands":[1,0,0,0,0,0,0,0,0,0]}}"""
        val s = LenientJson.decodeFromString(AppSettings.serializer(), v1)
        assertEquals("content://x", s.libraryTreeUri)
        assertEquals(8, s.crossfadeSec)
        assertEquals(1f, s.dsp.bands[0])
        assertNull(s.spotifyId)
        assertNull(s.spotifyKeys)
        assertEquals("a" to "b", s.copy(spotifyId = "a", spotifySecret = "b").spotifyKeys)
    }
}
