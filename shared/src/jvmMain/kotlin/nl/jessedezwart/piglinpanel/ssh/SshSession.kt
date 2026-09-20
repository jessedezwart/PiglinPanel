package nl.jessedezwart.piglinpanel.ssh

/** The output of one remote command, and whether it worked. */
data class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray) {
    fun stdoutText(): String = stdout.decodeToString()

    fun stderrText(): String = stderr.decodeToString()

    fun throwIfFailed() {
        if (exitCode != 0) {
            val error = stderrText()
            throw okio.IOException(error.ifBlank { "Remote command failed with exit $exitCode" })
        }
    }
}

/**
 * An authenticated SSH connection that can run commands.
 *
 * Separate from [SshClient] so that [SshConnectionPool], which is all lifecycle and no protocol,
 * can be exercised without a server to connect to.
 */
interface SshSession : AutoCloseable {
    /** Whether the transport is still up. False once the peer has hung up or the link has failed. */
    val isConnected: Boolean

    suspend fun exec(command: String): CommandResult

    /** Runs [command] feeding [bytes] to its stdin; throws with stderr text on non-zero exit. */
    suspend fun writeToCommand(command: String, bytes: ByteArray)
}
