package nl.jessedezwart.piglinpanel.curseforge

/**
 * CurseForge identifies files by a MurmurHash2 (32-bit, seed 1) of the file bytes with whitespace
 * stripped — tab, LF, CR and space are skipped before hashing, and the stripped length is what
 * seeds the hash.
 */
object CurseForgeFingerprint {
    private const val M = 0x5bd1e995
    private const val R = 24
    private const val SEED = 1

    private fun isWhitespace(byte: Byte): Boolean =
        byte == 0x9.toByte() || byte == 0xa.toByte() || byte == 0xd.toByte() || byte == 0x20.toByte()

    /** Returns the unsigned 32-bit fingerprint as a positive [Long]. */
    fun of(bytes: ByteArray): Long {
        val data = ByteArray(bytes.size)
        var length = 0
        for (byte in bytes) {
            if (!isWhitespace(byte)) {
                data[length++] = byte
            }
        }

        var h = SEED xor length
        var i = 0
        while (length - i >= 4) {
            var k = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)
            k *= M
            k = k xor (k ushr R)
            k *= M
            h *= M
            h = h xor k
            i += 4
        }

        when (length - i) {
            3 -> {
                h = h xor ((data[i + 2].toInt() and 0xff) shl 16)
                h = h xor ((data[i + 1].toInt() and 0xff) shl 8)
                h = h xor (data[i].toInt() and 0xff)
                h *= M
            }
            2 -> {
                h = h xor ((data[i + 1].toInt() and 0xff) shl 8)
                h = h xor (data[i].toInt() and 0xff)
                h *= M
            }
            1 -> {
                h = h xor (data[i].toInt() and 0xff)
                h *= M
            }
        }

        h = h xor (h ushr 13)
        h *= M
        h = h xor (h ushr 15)
        return h.toLong() and 0xffffffffL
    }
}
