package nl.jessedezwart.piglinpanel.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.StartConfig

class ServerStartCommandTest {
    @Test
    fun `detaches the command from the ssh session`() {
        val script = ServerStartCommand.detached("/srv/mc", "./start.sh")

        assertTrue(script.contains("setsid"), "runs in its own session")
        assertTrue(script.contains("nohup"), "survives the hangup when the channel closes")
        assertTrue(script.contains("< /dev/null"), "holds no stream from the ssh channel")
        assertTrue(script.contains("> '${ServerStartCommand.LOG_FILE}' 2>&1"))
        assertTrue(script.trimEnd().endsWith("fi"), "reports back rather than waiting for the server")
    }

    @Test
    fun `reports whether the command was still alive`() {
        val script = ServerStartCommand.detached("/srv/mc", "./start.sh")

        assertTrue(script.contains("kill -0"))
        assertTrue(script.contains("echo '${ServerStartCommand.RUNNING_MARKER}'"))
        assertTrue(script.contains("tail -n 20 '${ServerStartCommand.LOG_FILE}'"))
    }

    @Test
    fun `runs a self-detaching command as typed`() {
        val script = ServerStartCommand.verbatim("/srv/mc", "screen -dmS mc ./start.sh")

        assertEquals("cd '/srv/mc' || exit 1\nscreen -dmS mc ./start.sh < /dev/null\n", script)
    }

    @Test
    fun `quotes a directory containing a quote`() {
        val script = ServerStartCommand.verbatim("/srv/jesse's server", "./start.sh")

        assertTrue(script.startsWith("cd '/srv/jesse'\"'\"'s server' || exit 1"), script)
    }

    @Test
    fun `lets the shell expand a home-relative directory`() {
        val script = ServerStartCommand.verbatim("~/minecraft", "./start.sh")

        // Quoting the tilde would make it a literal folder name that does not exist.
        assertTrue(script.startsWith("cd \"\$HOME\"/'minecraft' || exit 1"), script)
    }

    @Test
    fun `resolves the working directory against the server root`() {
        assertEquals("/srv/mc", ServerStartCommand.workingDirectory(profile("/srv/mc", "")))
        assertEquals("/srv/mc/bin", ServerStartCommand.workingDirectory(profile("/srv/mc/", "bin/")))
        assertEquals("/opt/mc", ServerStartCommand.workingDirectory(profile("/srv/mc", "/opt/mc")))
        assertEquals("~/mc", ServerStartCommand.workingDirectory(profile("/srv/mc", "~/mc")))
        assertEquals(".", ServerStartCommand.workingDirectory(profile("", "")))
    }

    private fun profile(rootPath: String, workingDirectory: String): ServerProfile = ServerProfile(
        rootPath = rootPath,
        start = StartConfig(command = "./start.sh", workingDirectory = workingDirectory),
    )
}
