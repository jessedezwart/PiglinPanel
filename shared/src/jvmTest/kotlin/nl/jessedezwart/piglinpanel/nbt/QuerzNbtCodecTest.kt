package nl.jessedezwart.piglinpanel.nbt

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.querz.nbt.io.NBTOutputStream
import net.querz.nbt.io.NamedTag
import net.querz.nbt.tag.CompoundTag
import net.querz.nbt.tag.Tag
import nl.jessedezwart.piglinpanel.compress.isGzip
import okio.IOException

class QuerzNbtCodecTest {
    private val codec = QuerzNbtCodec()

    private fun sampleNbt(gzip: Boolean): ByteArray {
        val compound = CompoundTag().apply {
            putString("motd", "Piglinpanel")
            putInt("count", 3)
        }
        val baos = ByteArrayOutputStream()
        val sink = if (gzip) GZIPOutputStream(baos) else baos
        NBTOutputStream(sink).use { it.writeTag(NamedTag("root", compound), Tag.DEFAULT_MAX_DEPTH) }
        return baos.toByteArray()
    }

    @Test
    fun roundTripsRawNbt() {
        val original = sampleNbt(gzip = false)
        val snbt = codec.toSnbt(original)
        assertTrue(snbt.contains("Piglinpanel"))

        val encoded = codec.fromSnbt(snbt, original)
        assertTrue(!isGzip(encoded))
        assertEquals(snbt, codec.toSnbt(encoded))
    }

    @Test
    fun preservesGzipCompression() {
        val original = sampleNbt(gzip = true)
        val snbt = codec.toSnbt(original)
        val encoded = codec.fromSnbt(snbt, original)
        assertTrue(isGzip(encoded))
        assertEquals(snbt, codec.toSnbt(encoded))
    }

    @Test
    fun rejectsInvalidSnbt() {
        val failure = assertFailsWith<IOException> {
            codec.fromSnbt("{unclosed", sampleNbt(gzip = false))
        }
        assertTrue(failure.message!!.contains("Invalid SNBT"))
    }
}
