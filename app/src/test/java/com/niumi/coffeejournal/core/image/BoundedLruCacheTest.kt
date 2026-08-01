package com.niumi.coffeejournal.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruCacheTest {
    @Test
    fun `cache reuses entries and evicts least recently used`() {
        val cache = BoundedLruCache<String, String>(2)
        cache.put("a", "A")
        cache.put("b", "B")

        assertEquals("A", cache.get("a"))
        cache.put("c", "C")

        assertEquals("A", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("C", cache.get("c"))
        assertEquals(2, cache.size)
    }
}
