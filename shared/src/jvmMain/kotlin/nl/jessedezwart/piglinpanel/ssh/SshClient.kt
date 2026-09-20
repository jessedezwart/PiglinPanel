package nl.jessedezwart.piglinpanel.ssh

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.userauth.UserAuthException
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import okio.IOException

/**
 * A connected SSH session, used to run commands on a remote server.
 *
 * Host keys are checked against `~/.ssh/known_hosts` with the same trust-on-first-use rule as
 * OpenSSH's `StrictHostKeyChecking=accept-new` — see [AcceptNewHostKeyVerifier].
 */
class SshClient private constructor(private val ssh: SSHClient) : SshSession {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        suspend fun connect(profile: ServerProfile): SshClient = withContext(Dispatchers.IO) {
            val ssh = SSHClient()
            try {
                ssh.addHostKeyVerifier(AcceptNewHostKeyVerifier())
                ssh.connectTimeout = CONNECT_TIMEOUT_MS
                ssh.connect(profile.ssh.host, profile.ssh.port)
                authenticate(ssh, profile.ssh)
                SshClient(ssh)
            } catch (e: java.io.IOException) {
                ssh.closeQuietly()
                throw IOException(e.message?.let { "Could not connect over SSH: $it" } ?: "Could not connect over SSH", e)
            }
        }

        /**
         * Picks a single authentication method: an explicit key file, else a password, else the
         * default identities in `~/.ssh` — the same keys `ssh host` would offer. Keys that cannot
         * be read or parsed are skipped by sshj rather than failing the connection.
         */
        private fun authenticate(ssh: SSHClient, config: SshConfig) {
            val passphrase = config.privateKeyPassphrase.takeIf { it.isNotBlank() }
            when {
                config.privateKeyPath.isNotBlank() -> {
                    val location = SshIdentities.expandHome(config.privateKeyPath)
                    val keys = if (passphrase == null) {
                        ssh.loadKeys(location)
                    } else {
                        ssh.loadKeys(location, passphrase.toCharArray())
                    }
                    ssh.authPublickey(config.username, keys)
                }

                config.password.isNotBlank() -> ssh.authPassword(config.username, config.password)

                else -> {
                    val defaults = SshIdentities.defaultKeys()
                    if (defaults.isEmpty()) {
                        throw UserAuthException(
                            "No password set and no private keys in ${SshIdentities.sshDirectory()}",
                        )
                    }
                    ssh.authPublickey(config.username, *defaults.map { it.path }.toTypedArray())
                }
            }
        }
    }

    override val isConnected: Boolean get() = ssh.isConnected

    override suspend fun exec(command: String): CommandResult = withContext(Dispatchers.IO) {
        runCommand(command) { }
    }

    override suspend fun writeToCommand(command: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        val result = runCommand(command) {
            it.outputStream.use { stdin ->
                stdin.write(bytes)
                // Closing is not enough to get the last packet out. sshj flushes on close without
                // waiting for the remote window to reopen, and throws away whatever is still
                // buffered when it has not — then sends EOF anyway, so the far end sees a clean
                // end of stream and a short file. Flushing explicitly blocks for the window.
                stdin.flush()
            }
        }
        if (result.exitCode != 0) {
            throw IOException(result.stderrText().ifBlank { "Remote command failed with exit ${result.exitCode}" })
        }
    }

    /**
     * Each command gets its own SSH session — the protocol allows only one command per session —
     * and [feedStdin] runs before the output is drained so a writer does not deadlock against a
     * full pipe.
     */
    private fun runCommand(command: String, feedStdin: (Session.Command) -> Unit): CommandResult = try {
        ssh.startSession().use { session ->
            val remote = session.exec(command)
            feedStdin(remote)
            val stdout = IOUtils.readFully(remote.inputStream).toByteArray()
            val stderr = IOUtils.readFully(remote.errorStream).toByteArray()
            remote.join(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // The exit status only arrives with the channel close message.
            remote.close()
            CommandResult(remote.exitStatus ?: -1, stdout, stderr)
        }
    } catch (e: java.io.IOException) {
        throw IOException(e.message ?: "SSH command failed", e)
    }

    override fun close() {
        ssh.closeQuietly()
    }
}

private const val COMMAND_TIMEOUT_SECONDS = 60L

/** Disconnects without letting a failing teardown mask the error that caused it. */
private fun SSHClient.closeQuietly() {
    try {
        if (isConnected) disconnect()
    } catch (e: java.io.IOException) {
        // Already gone; nothing left to release.
    }
}
