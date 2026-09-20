package nl.jessedezwart.piglinpanel.fs

import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshConnectionPool
import okio.IOException

class SshServerFileSystem(
    private val profile: ServerProfile,
    private val ssh: SshConnectionPool,
) : ServerFileSystem {
    private val root: String = normalizeRemote(profile.rootPath)

    /**
     * Reads share one pooled connection, so the cost of overlapping them is a channel rather than
     * a handshake. The ceiling is sshd's `MaxSessions`, ten by default, and asking for more open
     * channels than the server allows fails the extra ones outright.
     */
    override val readConcurrency: Int get() = 6

    override suspend fun list(relativePath: String): List<FileEntry> {
        val remote = scoped(relativePath)
        val command =
            "find ${quote(remote)} -maxdepth 1 -mindepth 1 -printf '%P\\t%y\\t%s\\t%T@\\n' | sort"
        return ssh.withSession(profile) { client ->
            val result = client.exec(command)
            result.throwIfFailed()
            result.stdoutText()
                .split(Regex("\\R"))
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("\t", limit = 4)
                    if (parts.size < 4) return@mapNotNull null
                    val directory = parts[1] == "d"
                    FileEntry(
                        path = join(relativePath, parts[0]),
                        name = parts[0],
                        directory = directory,
                        size = if (directory) 0 else parts[2].toLongOrNull() ?: 0,
                        modifiedAt = parts[3].toDoubleOrNull()
                            ?.let { Instant.fromEpochMilliseconds((it * 1000).toLong()) },
                    )
                }
        }
    }

    override suspend fun readBytes(relativePath: String): ByteArray =
        ssh.withSession(profile) { client ->
            val result = client.exec("cat ${quote(scoped(relativePath))}")
            result.throwIfFailed()
            result.stdout
        }

    /**
     * Writes to a temporary file, checks it is the length it should be, and only then moves it into
     * place.
     *
     * The size check is not paranoia about the network — SSH checks its own integrity — but about
     * the stream ending early, which looks like success from both ends: the writer thinks it sent
     * everything, `cat` sees a clean end of input and exits zero. A jar cut short that way still
     * reads as a valid mod here, because a zip's directory lives at the end and nothing in a scan
     * goes looking for it, so the truncation would first surface as the server failing to boot.
     */
    override suspend fun writeBytes(relativePath: String, bytes: ByteArray) {
        val target = scoped(relativePath)
        val tmp = "$target.piglinpanel-tmp"
        val command = buildString {
            append("mkdir -p ${quote(parent(target))} && cat > ${quote(tmp)} || exit 1\n")
            append("written=\$(wc -c < ${quote(tmp)})\n")
            append("if [ \"\$written\" -ne ${bytes.size} ]; then\n")
            append("  rm -f -- ${quote(tmp)}\n")
            append("  echo \"wrote \$written of ${bytes.size} bytes\" >&2\n")
            append("  exit 1\n")
            append("fi\n")
            append("mv -f -- ${quote(tmp)} ${quote(target)}\n")
        }
        ssh.withSession(profile) { client ->
            client.writeToCommand(command, bytes)
        }
    }

    override suspend fun delete(relativePath: String) {
        ssh.withSession(profile) { client ->
            client.exec("rm -f -- ${quote(scoped(relativePath))}").throwIfFailed()
        }
    }

    override suspend fun move(sourceRelativePath: String, targetRelativePath: String) {
        val target = scoped(targetRelativePath)
        val command =
            "mkdir -p ${quote(parent(target))} && mv -f -- ${quote(scoped(sourceRelativePath))} ${quote(target)}"
        ssh.withSession(profile) { client ->
            client.exec(command).throwIfFailed()
        }
    }

    private fun scoped(relativePath: String): String {
        val clean = relativePath.replace('\\', '/')
        if (clean.startsWith("/") || clean.contains("..")) {
            throw IOException("Path escapes server root: $relativePath")
        }
        return if (clean.isBlank()) root else "$root/$clean"
    }

    private companion object {
        fun normalizeRemote(path: String): String {
            var clean = path.ifBlank { "." }.replace('\\', '/')
            while (clean.endsWith("/") && clean.length > 1) {
                clean = clean.dropLast(1)
            }
            return clean
        }

        fun parent(path: String): String {
            val slash = path.lastIndexOf('/')
            return if (slash <= 0) "." else path.substring(0, slash)
        }

        fun join(first: String, second: String): String = when {
            first.isBlank() -> second
            first.endsWith("/") -> first + second
            else -> "$first/$second"
        }

        fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
