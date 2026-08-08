package nl.jessedezwart.piglinpanel.fs

import nl.jessedezwart.piglinpanel.compress.gzipDecompress
import nl.jessedezwart.piglinpanel.model.FileEntry
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.nbt.NbtCodec
import nl.jessedezwart.piglinpanel.nbt.NbtFiles

class FileService(
    private val fileSystems: ServerFileSystemProvider,
    private val nbtCodec: NbtCodec,
) {
    suspend fun list(profile: ServerProfile, relativePath: String): List<FileEntry> =
        fileSystems.fileSystem(profile).list(relativePath)

    suspend fun readText(profile: ServerProfile, relativePath: String): String {
        var bytes = fileSystems.fileSystem(profile).readBytes(relativePath)
        if (relativePath.lowercase().endsWith(".gz")) {
            bytes = gzipDecompress(bytes)
        }
        if (NbtFiles.isNbtExtension(relativePath)) {
            return nbtCodec.toSnbt(bytes)
        }
        return bytes.decodeToString()
    }

    /**
     * Reads [relativePath] for the editor, refusing anything that is not text. [readText] decodes
     * blindly, so a jar or a region file turns into megabytes of replacement characters that the
     * editor then tries to lay out and highlight — enough to hang the UI.
     */
    suspend fun readEditable(profile: ServerProfile, relativePath: String): FileContent {
        var bytes = fileSystems.fileSystem(profile).readBytes(relativePath)
        if (relativePath.lowercase().endsWith(".gz")) {
            bytes = gzipDecompress(bytes)
        }
        // NBT is binary by nature but has a text form, so it is decoded rather than refused.
        if (NbtFiles.isNbtExtension(relativePath)) return FileContent.Text(nbtCodec.toSnbt(bytes))
        if (looksBinary(bytes)) return FileContent.Binary
        return FileContent.Text(bytes.decodeToString())
    }

    suspend fun readBytes(profile: ServerProfile, relativePath: String): ByteArray =
        fileSystems.fileSystem(profile).readBytes(relativePath)

    suspend fun saveText(profile: ServerProfile, relativePath: String, text: String) {
        fileSystems.fileSystem(profile).writeBytes(relativePath, text.encodeToByteArray())
    }

    suspend fun saveNbt(profile: ServerProfile, relativePath: String, snbt: String) {
        val fileSystem = fileSystems.fileSystem(profile)
        val original = fileSystem.readBytes(relativePath)
        fileSystem.writeBytes(relativePath, nbtCodec.fromSnbt(snbt, original))
    }

    suspend fun delete(profile: ServerProfile, relativePath: String) {
        fileSystems.fileSystem(profile).delete(relativePath)
    }

    suspend fun move(profile: ServerProfile, source: String, target: String) {
        fileSystems.fileSystem(profile).move(source, target)
    }
}

/** Outcome of opening a file for editing. */
sealed interface FileContent {
    data class Text(val value: String) : FileContent

    /** The file is not text and must not be loaded into the editor. */
    data object Binary : FileContent
}

/** How much of a file is sampled when deciding whether it is binary. */
private const val SNIFF_BYTES = 8192

/**
 * Applies the `git`/`file` heuristic: a NUL byte near the start means binary. Backed up by a count
 * of undecodable bytes, which catches formats that happen to avoid NUL in their header.
 */
private fun looksBinary(bytes: ByteArray): Boolean {
    val sample = bytes.copyOf(minOf(bytes.size, SNIFF_BYTES))
    if (sample.isEmpty()) return false
    if (sample.any { it == 0.toByte() }) return true
    // decodeToString() maps every malformed sequence to U+FFFD; a little is odd, a lot is binary.
    val replacements = sample.decodeToString().count { it == '�' }
    return replacements * 100 > sample.size
}
