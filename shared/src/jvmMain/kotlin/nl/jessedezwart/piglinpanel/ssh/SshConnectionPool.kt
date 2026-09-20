package nl.jessedezwart.piglinpanel.ssh

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import okio.IOException

/**
 * Keeps one SSH connection per set of credentials and lends it out, rather than paying a handshake
 * for every command.
 *
 * Getting in the door — a TCP round trip, a key exchange, an authentication — costs far more than
 * most of the commands this app runs. Hashing a mod folder makes that plain: one `cat` per jar,
 * hundreds of jars, and nearly all of the time spent connecting. SSH multiplexes channels over one
 * transport, so those reads can share a connection and still overlap each other, the way OpenSSH's
 * own `ControlMaster` does.
 *
 * A pooled connection may be gone by the time it is next used, since the server decides how long
 * to hold one. A command that fails on a connection that turns out to be down never reached the
 * server, so it is retried once on a fresh connection; a command that fails on a connection still
 * standing failed on its own merits and is reported as it is.
 *
 * Idle connections are closed on the way into the next call rather than by a timer, so the pool
 * needs no lifecycle of its own beyond [close] — which the app owns and calls on shutdown.
 */
class SshConnectionPool(
    private val idleTimeout: Duration = 1.minutes,
    private val connect: suspend (ServerProfile) -> SshSession = { SshClient.connect(it) },
) : AutoCloseable {
    private class Entry(val session: SshSession) {
        /** How many commands are using this connection right now; it cannot be closed above zero. */
        var leases = 0

        /** When the last lease was given back, or null while it is in use. */
        var idleSince: TimeSource.Monotonic.ValueTimeMark? = TimeSource.Monotonic.markNow()
    }

    private class Lease(val entry: Entry, val opened: Boolean) {
        val session get() = entry.session
    }

    private val lock = Mutex()
    private val entries = HashMap<SshConfig, Entry>()

    /**
     * Held for as long as it takes to hand out a connection for one set of credentials, so that a
     * burst of calls arriving on a cold pool opens one connection between them and not one each.
     * Connecting is exactly the cost being avoided, so paying it several times over would defeat
     * the pool on the one path that needs it most.
     */
    private val gates = HashMap<SshConfig, Mutex>()

    /** Runs [block] on a connection to [profile], reusing an open one when there is one. */
    suspend fun <T> withSession(profile: ServerProfile, block: suspend (SshSession) -> T): T {
        closeIdle()
        val lease = acquire(profile)
        try {
            return block(lease.session)
        } catch (e: IOException) {
            if (lease.opened || lease.session.isConnected) throw e
            discard(profile.ssh, lease.entry)
        } finally {
            release(lease.entry)
        }
        // Only reached when a connection taken from the pool turned out to be down, which means
        // the command never ran.
        val retry = acquire(profile)
        try {
            return block(retry.session)
        } finally {
            release(retry.entry)
        }
    }

    private suspend fun acquire(profile: ServerProfile): Lease {
        val key = profile.ssh
        val gate = lock.withLock { gates.getOrPut(key) { Mutex() } }
        return gate.withLock {
            val live = lock.withLock { entries[key]?.takeIf { it.session.isConnected } }
            if (live != null) {
                lock.withLock {
                    live.leases++
                    live.idleSince = null
                }
                return@withLock Lease(live, opened = false)
            }
            // Whatever was there is unusable. Dropped before connecting, so a failure to connect
            // does not leave a dead entry behind for the next caller to trip over.
            lock.withLock { entries.remove(key) }?.closeQuietly()
            val entry = Entry(connect(profile))
            lock.withLock {
                entry.leases++
                entry.idleSince = null
                entries[key] = entry
            }
            Lease(entry, opened = true)
        }
    }

    private suspend fun release(entry: Entry) {
        lock.withLock {
            entry.leases--
            if (entry.leases <= 0) entry.idleSince = TimeSource.Monotonic.markNow()
        }
    }

    private suspend fun discard(key: SshConfig, entry: Entry) {
        val dropped = lock.withLock { if (entries[key] === entry) entries.remove(key) else null }
        dropped?.closeQuietly()
    }

    /** Closes connections nobody is using that have sat unused for longer than [idleTimeout]. */
    private suspend fun closeIdle() {
        val expired = lock.withLock {
            val stale = entries.filterValues { entry ->
                entry.leases <= 0 && (entry.idleSince?.elapsedNow() ?: Duration.ZERO) >= idleTimeout
            }
            stale.keys.forEach { entries.remove(it) }
            stale.values
        }
        expired.forEach { it.closeQuietly() }
    }

    /**
     * Drops every connection, in use or not, without waiting for the lock. For shutdown, where
     * anything still running is being torn down anyway and would only fail a moment later.
     */
    override fun close() {
        val open = entries.values.toList()
        entries.clear()
        gates.clear()
        open.forEach { it.closeQuietly() }
    }

    /** How many connections the pool is holding open. For tests and diagnostics. */
    val openConnections: Int get() = entries.size

    private fun Entry.closeQuietly() {
        try {
            session.close()
        } catch (e: Exception) {
            // Already gone, or going. There is nothing left to release either way.
        }
    }
}
