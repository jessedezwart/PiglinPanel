package nl.jessedezwart.piglinpanel.control

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerAction
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Runs a shell script on the machine hosting a profile. Implemented over SSH on the JVM. */
fun interface RemoteShell {
    suspend fun run(profile: ServerProfile, script: String): ShellResult
}

sealed interface ActionResult {
    /** [output] is what the command printed, stdout then stderr; empty when it was silent. */
    data class Succeeded(val output: String) : ActionResult

    /** [message] is meant for the user: the command's own output where there is any. */
    data class Failed(val message: String) : ActionResult
}

/** Runs a profile's configured actions on the server's host. */
class ServerActionService(private val shell: RemoteShell) {
    /** Why actions cannot run for this profile, or null when they can. */
    fun unavailableReason(profile: ServerProfile): String? =
        if (profile.accessType != AccessType.SSH) {
            "Actions run on the server's host, so they only work for profiles reached over SSH."
        } else {
            null
        }

    /**
     * Runs [action]'s command in its working directory and waits for it to finish: an action is
     * expected to do its job and exit, and what it printed is the result worth showing.
     */
    suspend fun run(profile: ServerProfile, action: ServerAction): ActionResult {
        unavailableReason(profile)?.let { return ActionResult.Failed(it) }
        val command = action.command.trim()
        if (command.isBlank()) return ActionResult.Failed("This action has no command.")

        val directory = ActionScript.workingDirectory(profile, action.workingDirectory)
        val result = try {
            shell.run(profile, ActionScript.build(directory, command))
        } catch (e: IOException) {
            return ActionResult.Failed(e.message ?: "Could not reach the server over SSH.")
        }
        val output = listOf(result.stdout.trim(), result.stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        return if (result.exitCode == 0) {
            ActionResult.Succeeded(output)
        } else {
            ActionResult.Failed(output.ifBlank { "The command exited with status ${result.exitCode}." })
        }
    }
}
