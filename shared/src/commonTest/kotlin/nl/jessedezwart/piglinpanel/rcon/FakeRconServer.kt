package nl.jessedezwart.piglinpanel.rcon

/**
 * In-memory RCON "server" implementing [RconTransport]: decodes written requests and queues
 * scripted responses for subsequent reads.
 */
class FakeRconServer(
    private val password: String = "secret",
    private val onCommand: (String) -> String = { "ran: $it" },
) : RconTransport {
    private val pending = ArrayDeque<Byte>()
    var closed = false
        private set

    override suspend fun write(bytes: ByteArray) {
        val request = RconPacket.decode(bytes.copyOfRange(4, bytes.size))
        val response = when (request.type) {
            RconPacket.TYPE_AUTH ->
                if (request.body == password) {
                    RconPacket.encode(request.id, RconPacket.TYPE_COMMAND, "")
                } else {
                    RconPacket.encode(-1, RconPacket.TYPE_COMMAND, "")
                }
            RconPacket.TYPE_COMMAND ->
                RconPacket.encode(request.id, RconPacket.TYPE_RESPONSE, onCommand(request.body))
            else -> error("Unexpected request type ${request.type}")
        }
        response.forEach(pending::addLast)
    }

    override suspend fun readFully(count: Int): ByteArray {
        if (pending.size < count) {
            throw RconEofException("RCON connection closed by server")
        }
        return ByteArray(count) { pending.removeFirst() }
    }

    override fun close() {
        closed = true
    }
}
