package nl.jessedezwart.piglinpanel.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.ServerProfile

class ActionScriptTest {
    @Test
    fun `runs the command as typed in its directory`() {
        val script = ActionScript.build("/srv/mc", "docker compose restart")

        assertEquals("cd '/srv/mc' || exit 1\ndocker compose restart < /dev/null\n", script)
    }

    @Test
    fun `quotes a directory containing a quote`() {
        val script = ActionScript.build("/srv/jesse's server", "./restart.sh")

        assertTrue(script.startsWith("cd '/srv/jesse'\"'\"'s server' || exit 1"), script)
    }

    @Test
    fun `lets the shell expand a home-relative directory`() {
        val script = ActionScript.build("~/minecraft", "./restart.sh")

        // Quoting the tilde would make it a literal folder name that does not exist.
        assertTrue(script.startsWith("cd \"\$HOME\"/'minecraft' || exit 1"), script)
    }

    @Test
    fun `resolves the working directory against the server root`() {
        assertEquals("/srv/mc", ActionScript.workingDirectory(ServerProfile(rootPath = "/srv/mc"), ""))
        assertEquals("/srv/mc/bin", ActionScript.workingDirectory(ServerProfile(rootPath = "/srv/mc/"), "bin/"))
        assertEquals("/opt/mc", ActionScript.workingDirectory(ServerProfile(rootPath = "/srv/mc"), "/opt/mc"))
        assertEquals("~/mc", ActionScript.workingDirectory(ServerProfile(rootPath = "/srv/mc"), "~/mc"))
        assertEquals(".", ActionScript.workingDirectory(ServerProfile(rootPath = ""), ""))
    }
}
