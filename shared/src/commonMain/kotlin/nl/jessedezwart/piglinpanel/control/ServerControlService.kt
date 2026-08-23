package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs a shell script on the machine hosting a profile. Implemented over SSH on the JVM. */
fun interface RemoteShell {
    suspend fun run(profile: ServerProfile, script: String): ShellResult
}

sealed interface StartResult {
    data object Started : StartResult

    /** [message] is meant for the user: the server's own output where there is any. */
    data class Failed(val message: String) : StartResult
}

/** Starts a server on its host, using the command the profile carries. */
class ServerControlService(private val shell: RemoteShell) {
    /**
     * Why this profile cannot be started, or null when it can. The reason is shown next to the
     * disabled button, so it says what to do rather than just that something is missing.
     */
    fun unavailableReason(profile: ServerProfile): String? = when {
        profile.accessType != AccessType.SSH ->
            "Starting a server from the panel only works for profiles reached over SSH."

        profile.start.command.isBlank() ->
            "Set a start command in Settings to start this server from here."

        else -> null
    }

    suspend fun start(profile: ServerProfile): StartResult {
        unavailableReason(profile)?.let { return StartResult.Failed(it) }

        val directory = ServerStartCommand.workingDirectory(profile)
        val command = profile.start.command.trim()
        val script = if (profile.start.selfDetaching) {
            ServerStartCommand.verbatim(directory, command)
        } else {
            ServerStartCommand.detached(directory, command)
        }

        val result = try {
            shell.run(profile, script)
        } catch (e: IOException) {
            return StartResult.Failed(e.message ?: "Could not reach the server over SSH.")
        }

        if (result.exitCode != 0) {
            return StartResult.Failed(failureMessage(result))
        }
        if (profile.start.selfDetaching) {
            return StartResult.Started
        }
        // A wrapped launch always exits 0 — the shell forks and returns — so the script reports
        // back instead: no marker means the process was already gone two seconds later.
        if (result.stdout.lineSequence().any { it.trim() == ServerStartCommand.RUNNING_MARKER }) {
            return StartResult.Started
        }
        return StartResult.Failed(
            "The start command stopped immediately.\n\n" +
                result.stdout.trim().ifBlank {
                    "It printed nothing — check that ${command.substringBefore(' ')} exists in $directory" +
                        " and is executable."
                },
        )
    }

    private fun failureMessage(result: ShellResult): String =
        result.stderr.trim().ifBlank { result.stdout.trim() }
            .ifBlank { "The start command exited with status ${result.exitCode}." }
}
