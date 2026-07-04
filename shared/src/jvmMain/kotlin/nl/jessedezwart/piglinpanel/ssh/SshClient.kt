package nl.jessedezwart.piglinpanel.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

class SshClient private constructor(private val session: Session) : AutoCloseable {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        suspend fun connect(profile: ServerProfile): SshClient = withContext(Dispatchers.IO) {
            val config = profile.ssh
            try {
                val jsch = JSch()
                if (config.privateKeyPath.isNotBlank()) {
                    if (config.privateKeyPassphrase.isBlank()) {
                        jsch.addIdentity(config.privateKeyPath)
                    } else {
                        jsch.addIdentity(config.privateKeyPath, config.privateKeyPassphrase)
                    }
                }
                val session = jsch.getSession(config.username, config.host, config.port)
                if (config.password.isNotBlank()) {
                    session.setPassword(config.password)
                }
                session.setConfig(Properties().apply { put("StrictHostKeyChecking", "no") })
                session.connect(CONNECT_TIMEOUT_MS)
                SshClient(session)
            } catch (e: JSchException) {
                throw IOException("Could not connect over SSH", e)
            }
        }
    }

    data class CommandResult(val exitCode: Int, val stdout: ByteArray, val stderr: ByteArray) {
        fun stdoutText(): String = stdout.decodeToString()

        fun stderrText(): String = stderr.decodeToString()

        fun throwIfFailed() {
            if (exitCode != 0) {
                val error = stderrText()
                throw IOException(error.ifBlank { "Remote command failed with exit $exitCode" })
            }
        }
    }

    suspend fun exec(command: String): CommandResult = withContext(Dispatchers.IO) {
        try {
            val channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)
            channel.connect()
            while (!channel.isClosed) {
                Thread.sleep(20)
            }
            val exit = channel.exitStatus
            channel.disconnect()
            CommandResult(exit, stdout.toByteArray(), stderr.toByteArray())
        } catch (e: JSchException) {
            throw IOException("SSH command failed", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("SSH command interrupted", e)
        }
    }

    /** Runs [command] feeding [bytes] to its stdin; throws with stderr text on non-zero exit. */
    suspend fun writeToCommand(command: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            try {
                val channel = session.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                val stderr = ByteArrayOutputStream()
                channel.setErrStream(stderr)
                channel.connect()
                channel.outputStream.use { it.write(bytes) }
                while (!channel.isClosed) {
                    Thread.sleep(20)
                }
                val exit = channel.exitStatus
                channel.disconnect()
                if (exit != 0) {
                    throw IOException(stderr.toByteArray().decodeToString())
                }
            } catch (e: JSchException) {
                throw IOException("SSH write command failed", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("SSH write command interrupted", e)
            }
        }

    override fun close() {
        if (session.isConnected) {
            session.disconnect()
        }
    }
}
