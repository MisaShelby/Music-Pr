package fr.offlinetune.core.net

/** Small thread-safe LRU cache whose entries expire (stream URLs from YouTube die after a few hours). */
class ExpiringCache<K, V>(
    private val ttlMs: Long,
    private val maxSize: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val map = LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true)

    @Synchronized
    operator fun get(key: K): V? {
        val (at, v) = map[key] ?: return null
        if (clock() - at > ttlMs) { map.remove(key); return null }
        return v
    }

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = clock() to value
        while (map.size > maxSize) map.remove(map.keys.first())
    }

    @Synchronized
    fun invalidate(key: K) { map.remove(key) }
}
