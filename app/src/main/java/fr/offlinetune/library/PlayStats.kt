package fr.offlinetune.library

import fr.offlinetune.core.net.LenientJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class PlayEntry(val count: Int = 0, val lastPlayed: Long = 0)

/** Play counts and last play time per track uri (counted on this phone from v2.2 on). */
class PlayStats(private val entries: MutableMap<String, PlayEntry> = mutableMapOf()) {
    @Synchronized fun record(uri: String, now: () -> Long = System::currentTimeMillis) {
        val e = entries[uri] ?: PlayEntry()
        entries[uri] = e.copy(count = e.count + 1, lastPlayed = now())
    }

    @Synchronized fun count(uri: String) = entries[uri]?.count ?: 0
    @Synchronized fun lastPlayed(uri: String) = entries[uri]?.lastPlayed ?: 0
    @Synchronized fun mostPlayed(n: Int) = entries.entries.filter { it.value.count > 0 }
        .sortedWith(compareByDescending<Map.Entry<String, PlayEntry>> { it.value.count }.thenByDescending { it.value.lastPlayed }).take(n).map { it.key }
    @Synchronized fun recent(n: Int) = entries.entries.filter { it.value.lastPlayed > 0 }.sortedByDescending { it.value.lastPlayed }.take(n).map { it.key }

    @Synchronized fun toJson(): String = LenientJson.encodeToString(entries.toMap())

    companion object {
        fun fromJson(text: String) = PlayStats(LenientJson.decodeFromString<Map<String, PlayEntry>>(text).toMutableMap())
    }
}

/** Counts a play once per start of an item, after 30 s of listening or when the track (shorter) nears its end. */
class PlayCounter(private val stats: PlayStats, private val now: () -> Long = System::currentTimeMillis) {
    private var current: String? = null
    private var counted = false

    fun onItem(uri: String?) { current = uri; counted = false }

    /** @return true when this call recorded the play. */
    fun onProgress(uri: String?, positionMs: Long, durationMs: Long): Boolean {
        if (uri == null || uri != current || counted) return false
        val nearEnd = durationMs in 1..Long.MAX_VALUE && positionMs >= durationMs - 1_000
        if (positionMs >= 30_000 || nearEnd) {
            counted = true
            stats.record(uri, now)
            return true
        }
        return false
    }
}
