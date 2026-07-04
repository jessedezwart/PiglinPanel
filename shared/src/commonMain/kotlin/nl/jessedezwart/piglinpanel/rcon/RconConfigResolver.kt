package nl.jessedezwart.piglinpanel.rcon

import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.RconConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

/** Derives the profile's RCON settings from the server's `server.properties`. */
class RconConfigResolver(private val fileSystems: ServerFileSystemProvider) {
    companion object {
        private const val DEFAULT_PORT = 25575
        private const val LOCAL_HOST = "127.0.0.1"
        private const val SERVER_PROPERTIES_PATH = "server.properties"
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
