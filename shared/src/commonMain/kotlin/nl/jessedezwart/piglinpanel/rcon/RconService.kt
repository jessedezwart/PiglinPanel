package nl.jessedezwart.piglinpanel.rcon

import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

class RconService(
    private val open: suspend (host: String, port: Int) -> RconTransport = ::openRconTransport,
) {
    /**
     * Executes [command] against the profile's RCON endpoint, retrying once if the server closes
     * the connection mid-exchange.
     */
    suspend fun execute(profile: ServerProfile, command: String): String {
        if (!profile.rcon.enabled) {
            throw IOException("RCON is not enabled for this server")
        }

        for (attempt in 1..2) {
            val client = RconClient.connect(profile.rcon, open)
            try {
                return client.command(command)
            } catch (e: RconEofException) {
                if (attempt == 2) {
                    throw e
                }
            } finally {
                client.close()
            }
        }
        throw IOException("RCON command failed after retries")
    }
}
