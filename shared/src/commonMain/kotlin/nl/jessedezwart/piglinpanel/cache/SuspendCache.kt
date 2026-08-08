package nl.jessedezwart.piglinpanel.cache

import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Small in-memory suspend-friendly cache with TTL, LRU eviction, and in-flight request
 * de-duplication. Replaces the ehcache/@Cacheable setup from the Spring version.
 */
class SuspendCache<K : Any, V>(
    private val ttl: Duration = Duration.INFINITE,
    private val maxEntries: Int = 1024,
) {
    private class Entry<V>(val value: V, val insertedAt: TimeSource.Monotonic.ValueTimeMark)

    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>()
    private val inFlight = HashMap<K, CompletableDeferred<V>>()

    suspend fun getOrPut(key: K, loader: suspend (K) -> V): V {
        var ownedLoad: CompletableDeferred<V>? = null
        val existingLoad: CompletableDeferred<V>? = mutex.withLock {
            val entry = entries[key]
            if (entry != null && entry.insertedAt.elapsedNow() < ttl) {
                // Refresh LRU position.
                entries.remove(key)
                entries[key] = entry
                return entry.value
            }
            entries.remove(key)
            inFlight[key] ?: run {
                ownedLoad = CompletableDeferred()
                inFlight[key] = ownedLoad
                null
            }
        }
        if (existingLoad != null) {
            // Another caller is already loading this key; share its result.
            return existingLoad.await()
        }

        val result = try {
            loader(key)
        } catch (t: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            ownedLoad!!.completeExceptionally(t)
            throw t
        }
        mutex.withLock {
            entries[key] = Entry(result, TimeSource.Monotonic.markNow())
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
            inFlight.remove(key)
        }
        ownedLoad!!.complete(result)
        return result
    }

    suspend fun getIfPresent(key: K): V? = mutex.withLock {
        val entry = entries[key] ?: return null
        if (entry.insertedAt.elapsedNow() >= ttl) {
            entries.remove(key)
            return null
        }
        entries.remove(key)
        entries[key] = entry
        entry.value
    }

    suspend fun put(key: K, value: V) {
        mutex.withLock {
            entries.remove(key)
            entries[key] = Entry(value, TimeSource.Monotonic.markNow())
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    /** Seeds the cache, e.g. from a persisted snapshot. Existing entries are left untouched. */
    suspend fun putAll(values: Map<K, V>) {
        mutex.withLock {
            val now = TimeSource.Monotonic.markNow()
            for ((key, value) in values) {
                if (key in entries) continue
                entries[key] = Entry(value, now)
            }
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    /** The live entries, least-recently-used first, with expired ones dropped. */
    suspend fun snapshot(): Map<K, V> = mutex.withLock {
        val live = LinkedHashMap<K, V>(entries.size)
        for ((key, entry) in entries) {
            if (entry.insertedAt.elapsedNow() < ttl) live[key] = entry.value
        }
        live
    }

    suspend fun invalidate(key: K) {
        mutex.withLock { entries.remove(key) }
    }

    suspend fun clear() {
        mutex.withLock { entries.clear() }
    }
}
