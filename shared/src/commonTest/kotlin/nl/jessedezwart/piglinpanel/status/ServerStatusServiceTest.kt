package nl.jessedezwart.piglinpanel.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.RconConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig

class ServerStatusServiceTest {
    private fun service(portOpen: Boolean) = ServerStatusService { _, _, _ -> portOpen }

    @Test
    fun prefersSshHostForRemoteServers() = runTest {
        val profile = ServerProfile(
            accessType = AccessType.SSH,
            ssh = SshConfig(host = "mc.example.test"),
        )

        val status = service(portOpen = true).statusFor(profile)

        assertEquals("mc.example.test", status.host)
        assertTrue(status.online)
    }

    @Test
    fun usesRconHostAndPortForLocalServersWhenAvailable() = runTest {
        val profile = ServerProfile(
            rcon = RconConfig(enabled = true, host = "lan-server.test", port = 25575),
        )

        val status = service(portOpen = true).statusFor(profile)

        assertEquals("lan-server.test", status.host)
        assertEquals(25575, status.port)
    }

    @Test
    fun fallsBackToDefaultMinecraftPortWhenRconDisabled() = runTest {
        val status = service(portOpen = false).statusFor(ServerProfile())

        assertEquals("127.0.0.1", status.host)
        assertEquals(25565, status.port)
    }

    @Test
    fun marksServerOfflineWhenPortIsClosed() = runTest {
        assertFalse(service(portOpen = false).statusFor(ServerProfile()).online)
    }
}
