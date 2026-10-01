package nl.jessedezwart.piglinpanel.rcon

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.RconConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

/** Derives the profile's RCON settings from the server's `server.properties`, and can switch RCON on there. */
class RconConfigResolver(
    private val fileSystems: ServerFileSystemProvider,
    private val generatePassword: () -> String = ::randomRconPassword,
) {
    companion object {
        private const val DEFAULT_PORT = 25575
        private const val LOCAL_HOST = "127.0.0.1"
        private const val SERVER_PROPERTIES_PATH = "server.properties"
    }

    /**
     * Turns RCON on in `server.properties` with a freshly generated password and returns the profile
     * carrying the new settings. A port the file already names is kept. The server only reads the
     * file when it starts, so a running server needs a restart before it listens.
     *
     * Throws [IOException] when the file cannot be read or written; nothing is created from scratch,
     * since a missing `server.properties` means the root path points somewhere else.
     */
    suspend fun activate(profile: ServerProfile): ServerProfile {
        val fileSystem = fileSystems.fileSystem(profile)
        val content = fileSystem.readBytes(SERVER_PROPERTIES_PATH).decodeToString()
        val port = parsePort(parseProperties(content)["rcon.port"])
        val updated = withProperties(
            content,
            linkedMapOf(
                "enable-rcon" to "true",
                "rcon.port" to port.toString(),
                "rcon.password" to generatePassword(),
            ),
        )
        fileSystem.writeBytes(SERVER_PROPERTIES_PATH, updated.encodeToByteArray())
        return apply(profile, parseProperties(updated))
    }

    /** [content] with each of [values] set: existing lines are rewritten in place, the rest appended. */
    internal fun withProperties(content: String, values: Map<String, String>): String {
        val remaining = LinkedHashMap(values)
        val lines = content.lines().map { line ->
            val key = propertyKey(line) ?: return@map line
            val value = remaining.remove(key) ?: return@map line
            "$key=$value"
        }.toMutableList()
        // Appended before the trailing blank line a file ending in a newline splits into.
        val insertAt = if (lines.lastOrNull()?.isBlank() == true) lines.size - 1 else lines.size
        lines.addAll(insertAt, remaining.map { (key, value) -> "$key=$value" })
        return lines.joinToString("\n")
    }

    private fun propertyKey(rawLine: String): String? {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
        val separator = line.indexOfFirst { it == '=' || it == ':' }
        return if (separator <= 0) null else line.take(separator).trim()
    }

    /** Returns the profile with refreshed RCON config, or null if server.properties is unreadable. */
    suspend fun refresh(profile: ServerProfile): ServerProfile? = try {
        apply(profile, parseProperties(readServerProperties(profile)))
    } catch (e: IOException) {
        null
    }

    internal fun apply(profile: ServerProfile, properties: Map<String, String>): ServerProfile {
        val enabled = properties["enable-rcon"]?.toBoolean() ?: false
        return profile.copy(
            rcon = RconConfig(
                enabled = enabled,
                host = hostFor(profile),
                port = parsePort(properties["rcon.port"]),
                password = if (enabled) properties["rcon.password"] ?: "" else "",
            ),
        )
    }

    private suspend fun readServerProperties(profile: ServerProfile): String =
        fileSystems.fileSystem(profile).readBytes(SERVER_PROPERTIES_PATH).decodeToString()

    internal fun parseProperties(content: String): Map<String, String> = buildMap {
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue
            val separator = line.indexOfFirst { it == '=' || it == ':' }
            if (separator <= 0) continue
            put(line.take(separator).trim(), line.substring(separator + 1).trim())
        }
    }

    private fun hostFor(profile: ServerProfile): String =
        if (profile.accessType == AccessType.SSH && profile.ssh.host.isNotBlank()) {
            profile.ssh.host
        } else {
            LOCAL_HOST
        }

    private fun parsePort(value: String?): Int = value?.trim()?.toIntOrNull() ?: DEFAULT_PORT
}

/**
 * A random UUID as 32 hex characters: [Uuid.random] draws from the platform's cryptographic
 * generator, which `kotlin.random.Random` does not, and hex keeps the value free of anything
 * `server.properties` would need escaped.
 */
@OptIn(ExperimentalUuidApi::class)
private fun randomRconPassword(): String = Uuid.random().toHexString()
