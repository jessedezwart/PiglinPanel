package nl.jessedezwart.piglinpanel.compress

/** Decompresses gzip data; throws [okio.IOException] if [bytes] is not valid gzip. */
expect fun gzipDecompress(bytes: ByteArray): ByteArray

fun isGzip(bytes: ByteArray): Boolean =
    bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B
