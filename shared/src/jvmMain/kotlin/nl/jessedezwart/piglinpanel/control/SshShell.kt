package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshClient

/** Runs the start script over SSH, one connection per run. */
class SshShell : RemoteShell {
    override suspend fun run(profile: ServerProfile, script: String): ShellResult =
        SshClient.connect(profile).use { client ->
            val result = client.exec(script)
            ShellResult(result.exitCode, result.stdoutText(), result.stderrText())
        }
}
