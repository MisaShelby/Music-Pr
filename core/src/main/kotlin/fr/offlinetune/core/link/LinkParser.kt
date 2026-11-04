package fr.offlinetune.core.link

import fr.offlinetune.core.model.Platform
import fr.offlinetune.core.model.Unsupported
import fr.offlinetune.core.net.Http

sealed interface ParsedInput {
    data class Link(val platform: Platform, val id: String, val url: String) : ParsedInput
    /** A short link (spotify.link…) whose target is only known after following redirects. */
    data class ShortLink(val platform: Platform, val url: String) : ParsedInput
    data class Query(val text: String) : ParsedInput
}

object LinkParser {
    private val URL = Regex("""https?://[^\s<>"]+""")
    private val SPOTIFY = Regex("""^https?://open\.spotify\.com/(?:intl-[a-z]{2}(?:-[A-Za-z]+)?/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]+)""")
    private val DEEZER = Regex("""^https?://(?:www\.)?deezer\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist|artist)/(\d+)""")
    private val YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val SHORT = mapOf(
        "spotify.link" to Platform.SPOTIFY, "spoti.fi" to Platform.SPOTIFY,
        "deezer.page.link" to Platform.DEEZER, "link.deezer.com" to Platform.DEEZER, "dzr.page.link" to Platform.DEEZER,
        "on.soundcloud.com" to Platform.SOUNDCLOUD,
    )

    fun isShortLink(url: String): Boolean = host(url) in SHORT

    fun parse(text: String): ParsedInput {
        val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ';')
            ?: return ParsedInput.Query(text.trim().replace(Regex("""\s+"""), " "))
        val host = host(url)
        SHORT[host]?.let { return ParsedInput.ShortLink(it, url) }

        SPOTIFY.find(url)?.let { m ->
            val (kind, id) = m.destructured
            if (kind != "track") throw Unsupported(unsupportedKind(kind))
            return ParsedInput.Link(Platform.SPOTIFY, id, "https://open.spotify.com/track/$id")
        }
        DEEZER.find(url)?.let { m ->
            val (kind, id) = m.destructured
            if (kind != "track") throw Unsupported(unsupportedKind(kind))
            return ParsedInput.Link(Platform.DEEZER, id, "https://www.deezer.com/track/$id")
        }
        when (host) {
            "youtu.be" -> {
                val id = path(url).trim('/').substringBefore('/')
                return youtube(Platform.YOUTUBE, id)
            }
            "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com" -> {
                val platform = if (host == "music.youtube.com") Platform.YOUTUBE_MUSIC else Platform.YOUTUBE
                val p = path(url)
                val id = when {
                    p.startsWith("/watch") -> query(url)["v"]
                    p.startsWith("/shorts/") || p.startsWith("/live/") || p.startsWith("/embed/") -> p.split('/')[2]
                    p.startsWith("/playlist") || p.startsWith("/browse") -> throw Unsupported(unsupportedKind("playlist"))
                    else -> null
                } ?: throw Unsupported("ce lien YouTube")
                return youtube(platform, id)
            }
            "soundcloud.com", "www.soundcloud.com", "m.soundcloud.com" -> {
                val parts = path(url).trim('/').split('/').filter { it.isNotEmpty() }
                if (parts.size >= 2 && parts[1] == "sets") throw Unsupported(unsupportedKind("playlist"))
                if (parts.size < 2) throw Unsupported("ce lien SoundCloud")
                val clean = "https://soundcloud.com/${parts[0]}/${parts[1]}"
                return ParsedInput.Link(Platform.SOUNDCLOUD, "${parts[0]}/${parts[1]}", clean)
            }
        }
        throw Unsupported("ce lien (plateformes acceptées : Spotify, Deezer, YouTube, YouTube Music, SoundCloud)")
    }

    /** Like [parse] but follows short links (spotify.link, deezer.page.link, on.soundcloud.com…). */
    suspend fun parseResolving(text: String): ParsedInput {
        val parsed = parse(text)
        if (parsed !is ParsedInput.ShortLink) return parsed
        val target = Http.resolveRedirect(parsed.url)
        val again = parse(target)
        if (again is ParsedInput.ShortLink) throw Unsupported("ce lien court")
        return again
    }

    private fun youtube(platform: Platform, id: String): ParsedInput.Link {
        if (!YT_ID.matches(id)) throw Unsupported("ce lien YouTube")
        val url = if (platform == Platform.YOUTUBE_MUSIC) "https://music.youtube.com/watch?v=$id" else "https://www.youtube.com/watch?v=$id"
        return ParsedInput.Link(platform, id, url)
    }

    private fun unsupportedKind(kind: String) = when (kind) {
        "album", "playlist" -> "albums et playlists (un morceau à la fois)"
        else -> "ce type de lien ($kind)"
    }

    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
    private fun path(url: String) = "/" + url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
    private fun query(url: String): Map<String, String> =
        url.substringAfter('?', "").substringBefore('#').split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
}
