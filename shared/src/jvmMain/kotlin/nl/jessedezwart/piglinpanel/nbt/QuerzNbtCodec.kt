package nl.jessedezwart.piglinpanel.nbt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import net.querz.nbt.io.NBTInputStream
import net.querz.nbt.io.NBTOutputStream
import net.querz.nbt.io.NamedTag
import net.querz.nbt.io.SNBTUtil
import net.querz.nbt.tag.Tag
import nl.jessedezwart.piglinpanel.compress.gzipDecompress
import nl.jessedezwart.piglinpanel.compress.isGzip
import okio.IOException

class QuerzNbtCodec : NbtCodec {
    override fun toSnbt(bytes: ByteArray): String {
        val raw = if (isGzip(bytes)) gzipDecompress(bytes) else bytes
        NBTInputStream(ByteArrayInputStream(raw)).use { stream ->
            val namedTag = stream.readTag(Tag.DEFAULT_MAX_DEPTH)
            return SNBTUtil.toSNBT(namedTag.tag)
        }
    }

    override fun fromSnbt(snbt: String, original: ByteArray): ByteArray {
        val tag: Tag<*> = try {
            SNBTUtil.fromSNBT(snbt)
        } catch (e: Exception) {
            throw IOException("Invalid SNBT: ${e.message}", e)
        }
        val gzip = isGzip(original)
        val rootName = readRootName(original)

        val baos = ByteArrayOutputStream()
        val sink = if (gzip) GZIPOutputStream(baos) else baos
        NBTOutputStream(sink).use { out ->
            out.writeTag(NamedTag(rootName, tag), Tag.DEFAULT_MAX_DEPTH)
        }
        return baos.toByteArray()
    }

    private fun readRootName(bytes: ByteArray): String {
        val raw = if (isGzip(bytes)) {
            try {
                gzipDecompress(bytes)
            } catch (e: IOException) {
                return ""
            }
        } else {
            bytes
        }
        return try {
            NBTInputStream(ByteArrayInputStream(raw)).use { stream ->
                stream.readTag(Tag.DEFAULT_MAX_DEPTH).name ?: ""
            }
        } catch (e: java.io.IOException) {
            ""
        }
    }
}
