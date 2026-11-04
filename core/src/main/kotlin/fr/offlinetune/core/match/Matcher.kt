package fr.offlinetune.core.match

import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.core.model.TrackInfo
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** Scores how well a source candidate matches a reference track (title, artist, duration). */
object Matcher {
    const val MAX_GAP_SEC = 5
    const val MIN_SCORE = 0.5

    private const val NOISE =
        """remaster(ed)?|radio edit|single version|album version|official( music)? (video|audio)|official|lyrics?|lyric video|clip officiel|audio|explicit|hd|hq|\d{4} remaster(ed)?|video( clip)?|visuali[sz]er|feat\.?|ft\.?|featuring"""
    private val BRACKET = Regex("""[(\[]([^)\]]*)[)\]]""")
    private val NOISE_FRAGMENT = Regex("""^\s*(?:.*\b(?:$NOISE)\b.*)\s*$""", RegexOption.IGNORE_CASE)
    private val FEAT_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)
    private val VARIANTS = listOf("live", "cover", "karaoke", "instrumental", "nightcore", "sped up", "slowed", "acoustic", "remix", "8d")
    private val ARTIST_SPLIT = Regex("""\s*(,|&|\bx\b|\bet\b|\band\b|\bfeat\b\.?|\bft\b\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)

    fun normalize(s: String): String {
        var t = s
        // drop bracketed noise: "(Remastered 2011)", "[Official Video]", "(feat. X)"
        t = BRACKET.replace(t) { m -> if (NOISE_FRAGMENT.matches(m.groupValues[1])) " " else m.value }
        // drop " - Radio Edit" style tails
        t = t.split(" - ").filterIndexed { i, part -> i == 0 || !NOISE_FRAGMENT.matches(part) }.joinToString(" - ")
        t = FEAT_TAIL.replace(t, "")
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        t = t.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        return t.trim().replace(Regex("""\s+"""), " ")
    }

    private fun cleanArtist(a: String) = a.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""VEVO$"""), "").trim()

    private fun firstArtist(a: String) = ARTIST_SPLIT.split(a).firstOrNull { it.isNotBlank() } ?: a

    private fun jaccard(a: String, b: String): Double {
        val x = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val y = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    private fun artistSim(ref: String, cand: String): Double {
        val r = normalize(ref); val c = normalize(cand)
        if (r.isEmpty() || c.isEmpty()) return 0.0
        val rc = r.replace(" ", ""); val cc = c.replace(" ", "")
        if (rc == cc || (rc.length >= 4 && cc.contains(rc))) return 1.0
        return jaccard(r, c)
    }

    fun score(refTitle: String, refArtist: String, refDurationSec: Int?, cand: SourceCandidate): SourceCandidate {
        val ref = normalize(refTitle)
        val artist = firstArtist(refArtist)
        val candArtist = cleanArtist(cand.artist)

        // Uploads often look like "Artist - Title": try both halves.
        val parts = cand.title.split(" - ", limit = 2)
        var titleSim = jaccard(ref, normalize(cand.title))
        var artistScore = artistSim(artist, candArtist)
        if (parts.size == 2) {
            titleSim = max(titleSim, max(jaccard(ref, normalize(parts[1])), jaccard(ref, normalize(parts[0]))))
            artistScore = max(artistScore, max(artistSim(artist, parts[0]), artistSim(artist, parts[1])))
        }

        val rawRef = refTitle.lowercase()
        val rawCand = cand.title.lowercase()
        val penalty = if (VARIANTS.any { Regex("""\b$it\b""").containsMatchIn(rawCand) && !rawRef.contains(it) }) 0.3 else 0.0

        val gap = if (refDurationSec != null && cand.durationSec != null) abs(refDurationSec - cand.durationSec) else null
        val score = (0.6 * titleSim + 0.4 * artistScore - penalty - 0.02 * (gap ?: 0)).coerceAtLeast(0.0)
        val reason = when {
            gap != null && gap > MAX_GAP_SEC -> "Écart de durée : $gap s"
            score < MIN_SCORE -> "Correspondance trop faible"
            else -> null
        }
        return cand.copy(score = score, rejectedReason = reason)
    }

    /** All candidates scored, accepted ones first, best first. */
    fun rank(ref: TrackInfo, cands: List<SourceCandidate>): List<SourceCandidate> =
        cands.map { score(ref.title, ref.artist, ref.durationSec, it) }
            .sortedWith(compareBy<SourceCandidate> { it.rejectedReason != null }.thenByDescending { it.score })
}
