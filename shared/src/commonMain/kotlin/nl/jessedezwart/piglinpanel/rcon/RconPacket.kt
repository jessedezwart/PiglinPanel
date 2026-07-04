package nl.jessedezwart.piglinpanel.rcon

import okio.IOException

/**
 * Source RCON protocol packet codec. Frames are little-endian:
 * `[length][id][type][body...][0][0]` where length counts everything after itself.
 */
data class RconPacket(val id: Int, val type: Int, val body: String) {
    companion object {
        const val TYPE_AUTH = 3
        const val TYPE_COMMAND = 2
        const val TYPE_RESPONSE = 0

        /** Encodes a full frame including the leading length field. */
        fun encode(id: Int, type: Int, body: String): ByteArray {
            val bodyBytes = body.encodeToByteArray()
            require(bodyBytes.all { it in 0..127 }) { "RCON payload must be ASCII" }
            val bodyLength = 4 + 4 + bodyBytes.size + 2
            val bytes = ByteArray(4 + bodyLength)
            writeIntLe(bytes, 0, bodyLength)
            writeIntLe(bytes, 4, id)
            writeIntLe(bytes, 8, type)
            bodyBytes.copyInto(bytes, 12)
            // Trailing two null bytes are already zero-initialised.
            return bytes
        }

        /** Decodes a packet from the bytes following the length field. */
        fun decode(payload: ByteArray): RconPacket {
            if (payload.size < 10) {
                throw IOException("Malformed RCON packet length ${payload.size}")
            }
            val id = readIntLe(payload, 0)
            val type = readIntLe(payload, 4)
            val body = payload.decodeToString(8, payload.size - 2)
            if (payload[payload.size - 2] != 0.toByte() || payload[payload.size - 1] != 0.toByte()) {
                throw IOException("Invalid RCON packet terminator")
            }
            return RconPacket(id, type, body)
        }

        fun readIntLe(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        private fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
    }
}
