package nl.jessedezwart.piglinpanel.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class SuspendCacheTest {
    @Test
    fun cachesLoadedValues() = runTest {
        val cache = SuspendCache<String, Int>()
        var loads = 0
        repeat(3) {
            assertEquals(7, cache.getOrPut("key") { loads++; 7 })
        }
        assertEquals(1, loads)
    }

    @Test
    fun deduplicatesConcurrentLoads() = runTest {
        val cache = SuspendCache<String, Int>()
        val gate = CompletableDeferred<Unit>()
        var loads = 0
        coroutineScope {
            val a = async { cache.getOrPut("key") { loads++; gate.await(); 1 } }
            val b = async { cache.getOrPut("key") { loads++; gate.await(); 2 } }
            gate.complete(Unit)
            assertEquals(1, a.await())
            assertEquals(1, b.await())
        }
        assertEquals(1, loads)
    }

    @Test
    fun evictsLeastRecentlyUsedBeyondMaxEntries() = runTest {
        val cache = SuspendCache<String, Int>(maxEntries = 2)
        var loads = 0
        cache.getOrPut("a") { loads++; 1 }
        cache.getOrPut("b") { loads++; 2 }
        cache.getOrPut("a") { loads++; 1 } // touch a → b becomes eldest
        cache.getOrPut("c") { loads++; 3 } // evicts b
        cache.getOrPut("a") { loads++; 1 } // still cached
        cache.getOrPut("b") { loads++; 2 } // reloaded
        assertEquals(4, loads)
    }

    @Test
    fun failedLoadsAreNotCached() = runTest {
        val cache = SuspendCache<String, Int>()
        assertFailsWith<IllegalStateException> {
            cache.getOrPut("key") { error("boom") }
        }
        assertEquals(5, cache.getOrPut("key") { 5 })
    }

    @Test
    fun invalidateForcesReload() = runTest {
        val cache = SuspendCache<String, Int>()
        var loads = 0
        cache.getOrPut("key") { loads++; 1 }
        cache.invalidate("key")
        cache.getOrPut("key") { loads++; 1 }
        assertEquals(2, loads)
    }
}
