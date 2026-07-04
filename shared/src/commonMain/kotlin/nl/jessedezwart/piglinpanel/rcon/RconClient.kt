package nl.jessedezwart.piglinpanel.rcon

import nl.jessedezwart.piglinpanel.model.RconConfig
import okio.IOException

class RconEofException(message: String) : IOException(message)

/**
 * Minimal Source RCON client. Create via [RconClient.connect], which also authenticates; a failed
 * authentication throws [IOException].
 */
class RconClient private constructor(private val transport: RconTransport) : AutoCloseable {
    private var requestId = 100

    companion object {
        suspend fun connect(
            config: RconConfig,
            open: suspend (host: String, port: Int) -> RconTransport = ::openRconTransport,
        ): RconClient {
            val transport = open(config.host, config.port)
            val client = RconClient(transport)
            try {
                client.authenticate(config.password)
            } catch (t: Throwable) {
                transport.close()
                throw t
            }
            return client
        }
    }

    private suspend fun authenticate(password: String) {
        val id = ++requestId
        send(id, RconPacket.TYPE_AUTH, password)
        val response = readForId(id, allowAuthFailure = true)
        if (response.id == -1 || response.id != id) {
            throw IOException("RCON authentication failed")
        }
    }

    suspend fun command(command: String): String {
        val id = ++requestId
        send(id, RconPacket.TYPE_COMMAND, command)
        val response = readForId(id)
        if (response.type != RconPacket.TYPE_RESPONSE && response.type != RconPacket.TYPE_COMMAND) {
            throw IOException("Unexpected RCON response type ${response.type}")
        }
        return response.body
    }

    private suspend fun send(id: Int, type: Int, body: String) {
        transport.write(RconPacket.encode(id, type, body))
    }

    private suspend fun read(): RconPacket {
        val length = RconPacket.readIntLe(transport.readFully(4), 0)
        if (length < 10) {
            throw IOException("Malformed RCON packet length $length")
        }
        return RconPacket.decode(transport.readFully(length))
    }

    private suspend fun readForId(expectedId: Int, allowAuthFailure: Boolean = false): RconPacket {
        while (true) {
            val packet = read()
            if (packet.id == expectedId || (allowAuthFailure && packet.id == -1)) {
                return packet
            }
        }
    }

    override fun close() {
        transport.close()
    }
}
