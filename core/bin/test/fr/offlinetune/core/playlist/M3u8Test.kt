package fr.offlinetune.core.playlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class M3u8Test {
    @Test fun `write then parse round trip with accents and emoji`() {
        val entries = listOf(
            M3uEntry("../Joe Dassin - Été indien/Joe Dassin - Été indien.opus", 240, "Joe Dassin - Été indien"),
            M3uEntry("../Ñ 🎵/x.m4a", null, null),
        )
        val text = M3u8.write(entries)
        assertEquals(
            "#EXTM3U\n#EXTINF:240,Joe Dassin - Été indien\n../Joe Dassin - Été indien/Joe Dassin - Été indien.opus\n#EXTINF:-1,\n../Ñ 🎵/x.m4a\n",
            text,
        )
        assertEquals(listOf(entries[0], entries[1].copy(label = "")), M3u8.parse(text))
    }

    @Test fun `parse tolerates bom, crlf, backslashes, comments and missing extinf`() {
        val text = "﻿#EXTM3U\r\n#PLAYLIST:Mix\r\n\r\n#EXTINF:200,A - B\r\n..\\A - B\\A - B.mp3\r\nC.flac\r\n"
        val e = M3u8.parse(text)
        assertEquals(listOf("../A - B/A - B.mp3", "C.flac"), e.map { it.path })
        assertEquals(200, e[0].durationSec)
        assertNull(e[1].durationSec)
    }

    @Test fun `relative and resolve are inverse`() {
        val rel = M3u8.relative(listOf("Playlists"), listOf("Joe Dassin - Été indien", "x.opus"))
        assertEquals("../Joe Dassin - Été indien/x.opus", rel)
        assertEquals(listOf("Joe Dassin - Été indien", "x.opus"), M3u8.resolve(listOf("Playlists"), rel))
        assertEquals("b/c.mp3", M3u8.relative(listOf("a"), listOf("a", "b", "c.mp3")))
        assertEquals(listOf("a", "b", "c.mp3"), M3u8.resolve(listOf("a"), "./b/c.mp3"))
    }

    @Test fun `windows-1252 m3u is decoded, utf-8 kept`() {
        val latin = "#EXTM3U\nÉté.mp3\n".toByteArray(Charsets.ISO_8859_1)
        val (t1, c1) = M3u8.decode(latin)
        assertEquals("#EXTM3U\nÉté.mp3\n", t1); assertEquals("windows-1252", c1.name().lowercase())
        val (t2, c2) = M3u8.decode("Été 🎵.opus".toByteArray(Charsets.UTF_8))
        assertEquals("Été 🎵.opus", t2); assertEquals(Charsets.UTF_8, c2)
    }

    @Test fun `absolute and file url paths are normalised to segments`() {
        assertEquals(listOf("storage", "emulated", "0", "Music", "A - B", "A B.mp3"), M3u8.absoluteSegments("file:///storage/emulated/0/Music/A%20-%20B/A%20B.mp3"))
        assertEquals(listOf("sdcard", "Music", "x.mp3"), M3u8.absoluteSegments("/sdcard/Music/x.mp3"))
        assertEquals(null, M3u8.absoluteSegments("../x.mp3"))
    }
}
