package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshConnectionPool

/** Runs the start script over SSH, on the shared connection to the server. */
class SshShell(private val ssh: SshConnectionPool) : RemoteShell {
    override suspend fun run(profile: ServerProfile, script: String): ShellResult =
        ssh.withSession(profile) { client ->
            val result = client.exec(script)
            ShellResult(result.exitCode, result.stdoutText(), result.stderrText())
        }
}
