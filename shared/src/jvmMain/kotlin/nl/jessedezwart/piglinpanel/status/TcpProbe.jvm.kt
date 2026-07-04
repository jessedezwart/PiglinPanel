package nl.jessedezwart.piglinpanel.status

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun tcpProbe(host: String, port: Int, timeoutMillis: Int): Boolean =
    withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                true
            }
        } catch (e: IOException) {
            false
        }
    }
