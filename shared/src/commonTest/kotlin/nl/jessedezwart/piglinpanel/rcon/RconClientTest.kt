package nl.jessedezwart.piglinpanel.rcon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.RconConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

class RconClientTest {
    private val config = RconConfig(enabled = true, password = "secret")

    @Test
    fun authenticatesAndRunsCommands() = runTest {
        val server = FakeRconServer(password = "secret")
        val client = RconClient.connect(config) { _, _ -> server }
        assertEquals("ran: list", client.command("list"))
        client.close()
        assertTrue(server.closed)
    }

    @Test
    fun failsAuthenticationOnWrongPassword() = runTest {
        val server = FakeRconServer(password = "other")
        val failure = assertFailsWith<IOException> {
            RconClient.connect(config) { _, _ -> server }
        }
        assertTrue(failure.message!!.contains("authentication failed"))
        assertTrue(server.closed)
    }

    @Test
    fun serviceRejectsCommandsWhenRconIsDisabled() = runTest {
        val service = RconService { _, _ -> error("should not connect") }
        val profile = ServerProfile(rcon = RconConfig(enabled = false))
        val failure = assertFailsWith<IOException> { service.execute(profile, "list") }
        assertTrue(failure.message!!.contains("not enabled"))
    }

    @Test
    fun serviceRetriesOnceWhenServerClosesConnection() = runTest {
        var connects = 0
        val service = RconService { _, _ ->
            connects++
            if (connects == 1) {
                // First connection: authenticates fine, then dies on the command.
                object : RconTransport {
                    val delegate = FakeRconServer(password = "secret")
                    var reads = 0
                    override suspend fun write(bytes: ByteArray) = delegate.write(bytes)
                    override suspend fun readFully(count: Int): ByteArray {
                        reads++
                        if (reads > 2) throw RconEofException("RCON connection closed by server")
                        return delegate.readFully(count)
                    }
                    override fun close() = delegate.close()
                }
            } else {
                FakeRconServer(password = "secret")
            }
        }
        val profile = ServerProfile(rcon = config)
        assertEquals("ran: list", service.execute(profile, "list"))
        assertEquals(2, connects)
    }
}
