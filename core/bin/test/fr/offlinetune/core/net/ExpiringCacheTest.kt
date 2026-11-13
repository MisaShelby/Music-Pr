package fr.offlinetune.core.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExpiringCacheTest {
    private var now = 0L
    private val cache = ExpiringCache<String, String>(ttlMs = 1000, maxSize = 2) { now }

    @Test fun `entries expire after the ttl`() {
        cache.put("a", "1")
        now = 999; assertEquals("1", cache["a"])
        now = 1001; assertNull(cache["a"])
    }

    @Test fun `invalidate removes an entry`() {
        cache.put("a", "1")
        cache.invalidate("a")
        assertNull(cache["a"])
    }

    @Test fun `oldest entry is evicted beyond max size`() {
        cache.put("a", "1"); cache.put("b", "2"); cache.put("c", "3")
        assertNull(cache["a"]); assertEquals("3", cache["c"])
    }
}
