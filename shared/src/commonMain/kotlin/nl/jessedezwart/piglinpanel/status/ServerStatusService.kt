package nl.jessedezwart.piglinpanel.status

import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile

/** Opens a TCP connection to [host]:[port]; true if it connects within [timeoutMillis]. */
expect suspend fun tcpProbe(host: String, port: Int, timeoutMillis: Int): Boolean

data class ServerStatus(val host: String, val port: Int, val online: Boolean)

/**
 * Determines whether a Minecraft server is actually online by opening a TCP connection to the
 * server port. A plain ICMP ping only tells us the host machine answers, which is why the status
 * dot used to stay green even when the Minecraft server itself was down.
 */
class ServerStatusService(
    private val probe: suspend (host: String, port: Int, timeoutMillis: Int) -> Boolean = ::tcpProbe,
) {
    companion object {
        private const val LOCAL_HOST = "127.0.0.1"
        private const val DEFAULT_MINECRAFT_PORT = 25565
        private const val TIMEOUT_MILLIS = 1500
    }

    suspend fun statusFor(profile: ServerProfile): ServerStatus {
        val host = resolveHost(profile)
        val port = resolvePort(profile)
        return ServerStatus(host, port, isOnline(host, port))
    }

    internal fun resolveHost(profile: ServerProfile): String {
        if (profile.accessType == AccessType.SSH && profile.ssh.host.isNotBlank()) {
            return profile.ssh.host.trim()
        }
        if (profile.rcon.enabled && profile.rcon.host.isNotBlank()) {
            return profile.rcon.host.trim()
        }
        return LOCAL_HOST
    }

    /**
     * Prefers the RCON port when enabled (it is only open while the server is running), otherwise
     * the default Minecraft game port.
     */
    internal fun resolvePort(profile: ServerProfile): Int =
        if (profile.rcon.enabled && profile.rcon.port > 0) profile.rcon.port else DEFAULT_MINECRAFT_PORT

    internal suspend fun isOnline(host: String, port: Int): Boolean {
        if (host.isBlank() || port <= 0) {
            return false
        }
        return probe(host, port, TIMEOUT_MILLIS)
    }
}
