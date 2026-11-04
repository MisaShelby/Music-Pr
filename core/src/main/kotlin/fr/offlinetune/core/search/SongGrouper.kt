package fr.offlinetune.core.search

import fr.offlinetune.core.match.Matcher
import kotlin.math.abs

/** Groups hits from several platforms into songs: same ISRC, or same normalized title + first artist and ≤ 3 s apart. */
object SongGrouper {
    private const val MAX_GAP_SEC = 3

    private class Group(val first: ProviderHit) {
        val hits = LinkedHashMap<Provider, ProviderHit>()
        val isrcs = mutableSetOf<String>()
        val title = Matcher.normalize(first.title)
        val artist = Matcher.normalize(Matcher.firstArtistOf(first.artist))
        val durations = mutableListOf<Int>()

        fun add(h: ProviderHit) {
            hits.putIfAbsent(h.provider, h)
            h.isrc?.let { isrcs += it.uppercase() }
            h.durationSec?.let { durations += it }
        }

        fun matches(h: ProviderHit): Boolean {
            val isrc = h.isrc?.uppercase()
            if (isrc != null && isrc in isrcs) return true
            if (isrc != null && isrcs.isNotEmpty()) return false // both have ISRCs and they differ
            if (Matcher.normalize(h.title) != title) return false
            if (Matcher.normalize(Matcher.firstArtistOf(h.artist)) != artist) return false
            val d = h.durationSec ?: return true
            return durations.isEmpty() || durations.any { abs(it - d) <= MAX_GAP_SEC }
        }
    }

    fun group(hits: List<ProviderHit>): List<SongResult> {
        val groups = mutableListOf<Group>()
        for (h in hits) {
            val g = groups.firstOrNull { it.matches(h) } ?: Group(h).also { groups += it }
            g.add(h)
        }
        return groups.map { g ->
            val best = CATALOG_PRIORITY.firstNotNullOfOrNull { g.hits[it] } ?: g.first
            SongResult(
                title = best.title,
                artist = best.artist,
                album = CATALOG_PRIORITY.firstNotNullOfOrNull { g.hits[it]?.album },
                durationSec = best.durationSec ?: g.durations.firstOrNull(),
                coverUrl = CATALOG_PRIORITY.firstNotNullOfOrNull { g.hits[it]?.coverUrl },
                hits = g.hits.toMap(),
            )
        }
    }
}
