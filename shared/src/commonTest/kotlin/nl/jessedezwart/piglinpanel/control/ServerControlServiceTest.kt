package nl.jessedezwart.piglinpanel.control

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.StartConfig
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

private fun running(): ShellResult = ShellResult(0, ServerStartCommand.RUNNING_MARKER + "\n", "")

class ServerControlServiceTest {
    @Test
    fun `starts a server whose command does not detach itself`() = runTest {
        val shell = FakeShell { running() }

        val result = ServerControlService(shell).start(sshProfile())

        assertIs<StartResult.Started>(result)
        assertContains(shell.script.orEmpty(), "setsid")
    }

    @Test
    fun `runs a self-detaching command without wrapping it`() = runTest {
        val shell = FakeShell { ShellResult(0, "", "") }
        val profile = sshProfile(command = "screen -dmS mc ./start.sh", selfDetaching = true)

        val result = ServerControlService(shell).start(profile)

        assertIs<StartResult.Started>(result)
        assertEquals("cd '/srv/mc' || exit 1\nscreen -dmS mc ./start.sh < /dev/null\n", shell.script)
    }

    @Test
    fun `reports what the server printed when it dies immediately`() = runTest {
        val shell = FakeShell { ShellResult(0, "sh: ./start.sh: Permission denied\n", "") }

        val result = ServerControlService(shell).start(sshProfile())

        assertIs<StartResult.Failed>(result)
        assertContains(result.message, "Permission denied")
    }

    @Test
    fun `explains an empty log when the command left no trace`() = runTest {
        val shell = FakeShell { ShellResult(0, "", "") }

        val result = ServerControlService(shell).start(sshProfile())

        assertIs<StartResult.Failed>(result)
        assertContains(result.message, "./start.sh")
        assertContains(result.message, "/srv/mc")
    }

    @Test
    fun `reports a working directory that does not exist`() = runTest {
        val shell = FakeShell { ShellResult(1, "", "sh: cd: /srv/mc: No such file or directory") }

        val result = ServerControlService(shell).start(sshProfile())

        assertIs<StartResult.Failed>(result)
        assertContains(result.message, "No such file or directory")
    }

    @Test
    fun `reports a failure to reach the server`() = runTest {
        val shell = FakeShell { throw IOException("Could not connect over SSH: timed out") }

        val result = ServerControlService(shell).start(sshProfile())

        assertIs<StartResult.Failed>(result)
        assertContains(result.message, "timed out")
    }

    @Test
    fun `refuses profiles that cannot be started, without connecting`() = runTest {
        val shell = FakeShell { running() }
        val service = ServerControlService(shell)
        val local = ServerProfile(rootPath = "/srv/mc", start = StartConfig(command = "./start.sh"))
        val commandless = sshProfile(command = "")

        assertIs<StartResult.Failed>(service.start(local))
        assertIs<StartResult.Failed>(service.start(commandless))
        assertNull(shell.script, "no connection is made for a profile that cannot be started")
        assertContains(service.unavailableReason(local).orEmpty(), "SSH")
        assertContains(service.unavailableReason(commandless).orEmpty(), "Settings")
        assertNull(service.unavailableReason(sshProfile()))
    }

    private fun sshProfile(
        command: String = "./start.sh",
        selfDetaching: Boolean = false,
    ): ServerProfile = ServerProfile(
        accessType = AccessType.SSH,
        rootPath = "/srv/mc",
        start = StartConfig(command = command, selfDetaching = selfDetaching),
    )
}
