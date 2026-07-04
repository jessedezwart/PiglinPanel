package nl.jessedezwart.piglinpanel.rcon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.IOException

class RconPacketTest {
    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = RconPacket.encode(id = 42, type = RconPacket.TYPE_COMMAND, body = "list")
        // Length prefix counts id + type + body + two null terminators.
        assertEquals(4 + 4 + 4 + 2, RconPacket.readIntLe(encoded, 0))

        val decoded = RconPacket.decode(encoded.copyOfRange(4, encoded.size))
        assertEquals(RconPacket(42, RconPacket.TYPE_COMMAND, "list"), decoded)
    }

    @Test
    fun encodesLittleEndian() {
        val encoded = RconPacket.encode(id = 1, type = RconPacket.TYPE_AUTH, body = "")
        assertEquals(10, encoded[0].toInt())
        assertEquals(0, encoded[1].toInt())
        assertEquals(1, encoded[4].toInt())
        assertEquals(RconPacket.TYPE_AUTH, encoded[8].toInt())
    }

    @Test
    fun rejectsTooShortPayload() {
        assertFailsWith<IOException> { RconPacket.decode(ByteArray(9)) }
    }

    @Test
    fun rejectsMissingTerminator() {
        val payload = RconPacket.encode(1, RconPacket.TYPE_RESPONSE, "ok").copyOfRange(4, 16).also {
            it[it.size - 1] = 1
        }
        assertFailsWith<IOException> { RconPacket.decode(payload) }
    }

    @Test
    fun rejectsNonAsciiPayload() {
        assertFailsWith<IllegalArgumentException> {
            RconPacket.encode(1, RconPacket.TYPE_COMMAND, "héllo")
        }
    }
}
