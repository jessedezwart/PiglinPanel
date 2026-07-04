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
