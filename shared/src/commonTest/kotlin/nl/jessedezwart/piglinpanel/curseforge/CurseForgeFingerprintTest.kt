package nl.jessedezwart.piglinpanel.curseforge

import kotlin.test.Test
import kotlin.test.assertEquals

class CurseForgeFingerprintTest {
    /** Expected values come from an independent transcription of the canonical MurmurHash2 C source. */
    @Test
    fun matchesMurmur2ReferenceValues() {
        assertEquals(1540447798L, CurseForgeFingerprint.of("".encodeToByteArray()))
        assertEquals(626045324L, CurseForgeFingerprint.of("a".encodeToByteArray()))
        assertEquals(1692487918L, CurseForgeFingerprint.of("ab".encodeToByteArray()))
        assertEquals(1621425345L, CurseForgeFingerprint.of("abc".encodeToByteArray()))
        assertEquals(3376380438L, CurseForgeFingerprint.of("abcd".encodeToByteArray()))
        assertEquals(2824650221L, CurseForgeFingerprint.of("hello world".encodeToByteArray()))
        assertEquals(
            3751777527L,
            CurseForgeFingerprint.of("The quick brown fox jumps over the lazy dog".encodeToByteArray()),
        )
    }

    @Test
    fun ignoresWhitespaceBytes() {
        assertEquals(
            CurseForgeFingerprint.of("hello world".encodeToByteArray()),
            CurseForgeFingerprint.of("hello \t\r\n world".encodeToByteArray()),
        )
    }

    /** The hash is an unsigned 32-bit value, so it must never come back negative. */
    @Test
    fun isAlwaysUnsigned() {
        for (size in 0..64) {
            val bytes = ByteArray(size) { (it * 37 - 128).toByte() }
            val fingerprint = CurseForgeFingerprint.of(bytes)
            assertEquals(true, fingerprint in 0..0xffffffffL, "fingerprint $fingerprint out of range")
        }
    }
}
