package nl.jessedezwart.piglinpanel.control

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

/** Records the script it was given and answers with a canned result. */
private class FakeShell(private val result: (String) -> ShellResult) : RemoteShell {
    var script: String? = null
        private set

    override suspend fun run(profile: ServerProfile, script: String): ShellResult {
        this.script = script
        return result(script)
    }
}

class ServerActionServiceTest {
    @Test
    fun `runs an action in its working directory and returns its output`() = runTest {
        val shell = FakeShell { ShellResult(0, "Restarting mc ... done\n", "") }
        val action = ServerAction(name = "Restart", command = "docker compose restart", workingDirectory = "docker")

        val result = ServerActionService(shell).run(sshProfile(), action)

        assertEquals(ActionResult.Succeeded("Restarting mc ... done"), result)
        assertEquals("cd '/srv/mc/docker' || exit 1\ndocker compose restart < /dev/null\n", shell.script)
    }

    @Test
    fun `reports a failing action with what it printed`() = runTest {
        val shell = FakeShell { ShellResult(1, "", "no configuration file provided\n") }

        val result = ServerActionService(shell).run(sshProfile(), ServerAction(command = "docker compose restart"))

        assertEquals(ActionResult.Failed("no configuration file provided"), result)
    }

    @Test
    fun `falls back to the exit status when a failing action printed nothing`() = runTest {
        val shell = FakeShell { ShellResult(3, "", "") }

        val result = ServerActionService(shell).run(sshProfile(), ServerAction(command = "false"))

        assertIs<ActionResult.Failed>(result)
        assertContains(result.message, "status 3")
    }

    @Test
    fun `reports an unreachable host`() = runTest {
        val shell = FakeShell { throw IOException("Connection refused") }

        val result = ServerActionService(shell).run(sshProfile(), ServerAction(command = "ls"))

        assertEquals(ActionResult.Failed("Connection refused"), result)
    }

    @Test
    fun `refuses actions on a local profile`() = runTest {
        val shell = FakeShell { ShellResult(0, "", "") }
        val service = ServerActionService(shell)

        val result = service.run(ServerProfile(rootPath = "/srv/mc"), ServerAction(command = "ls"))

        assertIs<ActionResult.Failed>(result)
        assertNull(shell.script)
        assertNull(service.unavailableReason(sshProfile()))
    }

    private fun sshProfile(): ServerProfile = ServerProfile(accessType = AccessType.SSH, rootPath = "/srv/mc")
}
