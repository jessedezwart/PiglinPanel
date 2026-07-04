package nl.jessedezwart.piglinpanel.fs

import kotlin.time.Instant
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.ssh.SshClient
import okio.IOException

class SshServerFileSystem(private val profile: ServerProfile) : ServerFileSystem {
    private val root: String = normalizeRemote(profile.rootPath)

    override suspend fun list(relativePath: String): List<FileEntry> {
        val remote = scoped(relativePath)
        val command =
            "find ${quote(remote)} -maxdepth 1 -mindepth 1 -printf '%P\\t%y\\t%s\\t%T@\\n' | sort"
        SshClient.connect(profile).use { client ->
            val result = client.exec(command)
            result.throwIfFailed()
            return result.stdoutText()
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

    override suspend fun readBytes(relativePath: String): ByteArray {
        SshClient.connect(profile).use { client ->
            val result = client.exec("cat ${quote(scoped(relativePath))}")
            result.throwIfFailed()
            return result.stdout
        }
    }

    override suspend fun writeBytes(relativePath: String, bytes: ByteArray) {
        val target = scoped(relativePath)
        val tmp = "$target.piglinpanel-tmp"
        val command =
            "mkdir -p ${quote(parent(target))} && cat > ${quote(tmp)} && mv ${quote(tmp)} ${quote(target)}"
        SshClient.connect(profile).use { client ->
            client.writeToCommand(command, bytes)
        }
    }

    override suspend fun delete(relativePath: String) {
        SshClient.connect(profile).use { client ->
            client.exec("rm -f -- ${quote(scoped(relativePath))}").throwIfFailed()
        }
    }

    override suspend fun move(sourceRelativePath: String, targetRelativePath: String) {
        val target = scoped(targetRelativePath)
        val command =
            "mkdir -p ${quote(parent(target))} && mv -f -- ${quote(scoped(sourceRelativePath))} ${quote(target)}"
        SshClient.connect(profile).use { client ->
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
