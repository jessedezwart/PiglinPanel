package nl.jessedezwart.piglinpanel.rcon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun openRconTransport(host: String, port: Int): RconTransport =
    withContext(Dispatchers.IO) { SocketRconTransport(Socket(host, port)) }

private class SocketRconTransport(private val socket: Socket) : RconTransport {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    override suspend fun readFully(count: Int): ByteArray = withContext(Dispatchers.IO) {
        val bytes = ByteArray(count)
        try {
            input.readFully(bytes)
        } catch (e: EOFException) {
            throw RconEofException("RCON connection closed by server")
        }
        bytes
    }

    override fun close() {
        socket.close()
    }
}
