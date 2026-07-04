package nl.jessedezwart.piglinpanel.compress

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

actual fun gzipDecompress(bytes: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readAllBytes() }
