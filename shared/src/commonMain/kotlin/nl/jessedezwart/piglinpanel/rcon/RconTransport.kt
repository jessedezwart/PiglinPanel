package nl.jessedezwart.piglinpanel.rcon

/** Raw byte transport for the RCON protocol; implemented per platform. */
interface RconTransport {
    suspend fun write(bytes: ByteArray)

    /** Reads exactly [count] bytes, or throws [okio.IOException] on EOF. */
    suspend fun readFully(count: Int): ByteArray

    fun close()
}

/** Opens a plain TCP connection to [host]:[port]; implemented per platform. */
expect suspend fun openRconTransport(host: String, port: Int): RconTransport
