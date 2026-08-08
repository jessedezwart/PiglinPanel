package nl.jessedezwart.piglinpanel.mods

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

actual fun readZipEntry(bytes: ByteArray, entryName: String): ByteArray? =
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName) {
                    found = zip.readBytes()
                    break
                }
            }
            found
        }
    } catch (e: Exception) {
        // A corrupt or non-zip file is not worth failing a scan over; it just has no metadata.
        null
    }
