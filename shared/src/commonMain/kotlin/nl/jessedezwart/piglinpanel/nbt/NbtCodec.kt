package nl.jessedezwart.piglinpanel.nbt

/**
 * Converts Minecraft NBT files to and from their SNBT text representation. Implementations handle
 * optional gzip compression and preserve the original root tag name and compression on save.
 */
interface NbtCodec {
    /** Renders NBT file [bytes] (gzipped or raw) as SNBT text. */
    fun toSnbt(bytes: ByteArray): String

    /**
     * Encodes [snbt] back into NBT file bytes, copying gzip compression and root tag name from the
     * [original] file content. Throws [okio.IOException] for invalid SNBT.
     */
    fun fromSnbt(snbt: String, original: ByteArray): ByteArray
}

object NbtFiles {
    fun isNbtExtension(path: String?): Boolean {
        if (path == null) return false
        val lower = path.lowercase()
        return lower.endsWith(".nbt") ||
            lower.endsWith(".dat") ||
            lower.endsWith(".dat_old") ||
            lower.endsWith(".mca")
    }
}
