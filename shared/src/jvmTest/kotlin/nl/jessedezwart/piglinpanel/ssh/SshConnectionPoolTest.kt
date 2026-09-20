package nl.jessedezwart.piglinpanel.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import okio.IOException

/** A connection that runs no commands, but can be told to go down or to fail one. */
private class FakeSession : SshSession {
    override var isConnected = true
    var closed = false

    /** Runs inside [exec] before it answers, so a test can hold a command open. */
    var onExec: (suspend () -> Unit)? = null

    /** Thrown by the next [exec], once. */
    var failNext: IOException? = null

    val commands = mutableListOf<String>()

    override suspend fun exec(command: String): CommandResult {
        commands += command
        onExec?.invoke()
        failNext?.let {
            failNext = null
            throw it
        }
        return CommandResult(0, byteArrayOf(), byteArrayOf())
    }

    override suspend fun writeToCommand(command: String, bytes: ByteArray) {
        exec(command)
    }

    override fun close() {
        closed = true
        isConnected = false
    }
}

private fun profile(host: String = "host", id: String = "p1") =
    ServerProfile(id = id, ssh = SshConfig(host = host, username = "me"))

class SshConnectionPoolTest {
    /** Sleeps on the wall clock, which is the one the pool measures idle time against. */
    private suspend fun realDelay(millis: Long) = withContext(Dispatchers.Default) { delay(millis) }

    private val opened = mutableListOf<FakeSession>()
    private val openLock = Mutex()

    /** Guards [opened] because the point of several tests here is to connect concurrently. */
    private suspend fun open(): FakeSession = FakeSession().also { openLock.withLock { opened += it } }

    private fun pool(
        idleTimeout: kotlin.time.Duration = 1.seconds,
        connect: suspend (ServerProfile) -> SshSession = { open() },
    ) = SshConnectionPool(idleTimeout, connect)

    @Test
    fun runsManyCommandsOverOneConnection() = runTest {
        val pool = pool()

        repeat(5) { n -> pool.withSession(profile()) { it.exec("command $n") } }

        assertEquals(1, opened.size)
        assertEquals(1, pool.openConnections)
        assertEquals((0..4).map { "command $it" }, opened.single().commands)
    }

    @Test
    fun opensOneConnectionForABurstThatArrivesBeforeAnyIsReady() = runTest(timeout = 10.seconds) {
        // The scan's parallel reads all arrive on a cold pool at once. Connecting is the cost the
        // pool exists to avoid, so paying it six times over here would defeat it where it matters.
        val allSixWaiting = CompletableDeferred<Unit>()
        var waiting = 0
        val gate = Mutex()
        // Connecting takes a moment, as it does against a real server, so all six callers are
        // inside the pool before the first connection is ready. That is the case that matters:
        // if they did not queue behind one another they would each open one.
        val pool = pool(connect = { realDelay(20); open() })

        coroutineScope {
            (1..6).map {
                async {
                    pool.withSession(profile()) { session ->
                        val n = gate.withLock { ++waiting }
                        if (n == 6) allSixWaiting.complete(Unit)
                        allSixWaiting.await()
                        session.exec("read $it")
                    }
                }
            }.awaitAll()
        }

        assertEquals(1, opened.size)
        assertEquals(6, opened.single().commands.size)
    }

    @Test
    fun keepsServersApart() = runTest {
        val pool = pool()

        pool.withSession(profile("one", "a")) { it.exec("x") }
        pool.withSession(profile("two", "b")) { it.exec("x") }
        pool.withSession(profile("one", "c")) { it.exec("x") }

        // Two hosts, two connections — and the third call reuses the first host's, not the second.
        assertEquals(2, opened.size)
        assertEquals(2, pool.openConnections)
        assertEquals(2, opened.first().commands.size)
    }

    @Test
    fun retriesOnAFreshConnectionWhenAPooledOneTurnedOutToBeDown() = runTest {
        val pool = pool()
        pool.withSession(profile()) { it.exec("first") }

        // What a server that hung up while the connection sat idle looks like: the command fails
        // and the transport is already down, so it never reached the far end.
        val stale = opened.single()
        stale.isConnected = false
        stale.failNext = IOException("connection reset")

        pool.withSession(profile()) { it.exec("second") }

        assertEquals(2, opened.size)
        assertTrue(stale.closed, "the dead connection should not be left in the pool")
        assertEquals(listOf("second"), opened.last().commands)
        assertEquals(1, pool.openConnections)
    }

    @Test
    fun reportsACommandThatFailedOnItsOwnMeritsWithoutRunningItTwice() = runTest {
        val pool = pool()
        pool.withSession(profile()) { it.exec("first") }
        val live = opened.single()
        live.failNext = IOException("rm: permission denied")

        val thrown = assertFailsWith<IOException> {
            pool.withSession(profile()) { it.exec("rm something") }
        }

        // The connection is still up, so the command really did fail. Retrying would run a second
        // time something that already happened once.
        assertEquals("rm: permission denied", thrown.message)
        assertEquals(1, opened.size)
        assertEquals(listOf("first", "rm something"), live.commands)
    }

    @Test
    fun closesAConnectionNobodyHasUsedForAWhile() = runTest(timeout = 10.seconds) {
        val pool = pool(idleTimeout = 50.milliseconds)
        pool.withSession(profile()) { it.exec("first") }
        val first = opened.single()

        realDelay(120)
        pool.withSession(profile()) { it.exec("second") }

        assertTrue(first.closed, "an idle connection should not be held open indefinitely")
        assertEquals(2, opened.size)
        assertEquals(1, pool.openConnections)
    }

    @Test
    fun keepsAConnectionThatIsStillInUseNoMatterHowLongTheCommandRuns() = runTest(timeout = 10.seconds) {
        val pool = pool(idleTimeout = 50.milliseconds)
        val slowStarted = CompletableDeferred<Unit>()
        val letSlowFinish = CompletableDeferred<Unit>()

        coroutineScope {
            val slow = async {
                pool.withSession(profile()) { session ->
                    (session as FakeSession).onExec = {
                        slowStarted.complete(Unit)
                        letSlowFinish.await()
                    }
                    session.exec("tail -f")
                }
            }
            slowStarted.await()
            realDelay(120)

            // A second caller arriving after the idle timeout must not sweep away a connection
            // that is mid-command; long-running work is exactly what the lease count protects.
            val held = opened.single()
            held.onExec = null
            pool.withSession(profile()) { it.exec("quick") }
            assertTrue(!held.closed)
            assertEquals(1, opened.size)

            letSlowFinish.complete(Unit)
            slow.await()
        }
    }

    @Test
    fun closeDropsEverythingItHolds() = runTest {
        val pool = pool()
        pool.withSession(profile("one", "a")) { it.exec("x") }
        pool.withSession(profile("two", "b")) { it.exec("x") }

        pool.close()

        assertEquals(0, pool.openConnections)
        assertTrue(opened.all { it.closed })
    }
}
