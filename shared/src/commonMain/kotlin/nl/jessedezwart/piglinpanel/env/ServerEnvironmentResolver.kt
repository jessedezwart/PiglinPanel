package nl.jessedezwart.piglinpanel.env

import nl.jessedezwart.piglinpanel.fs.ServerFileSystem
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException

/** Detects the server's mod loader and Minecraft version from files on the server root. */
class ServerEnvironmentResolver(private val fileSystems: ServerFileSystemProvider) {

    /**
     * Returns the profile with detected [ServerProfile.modLoader]/[ServerProfile.mcVersion] filled
     * in, or null if the server root is unreachable. Detected values never clobber an existing
     * (e.g. manually-set) value with an undetected one.
     */
    suspend fun refresh(profile: ServerProfile): ServerProfile? = try {
        apply(profile, detect(fileSystems.fileSystem(profile)))
    } catch (e: IOException) {
        null
    }

    internal fun apply(profile: ServerProfile, detected: Detected): ServerProfile = profile.copy(
        modLoader = detected.modLoader ?: profile.modLoader,
        mcVersion = detected.mcVersion ?: profile.mcVersion,
    )

    internal suspend fun detect(fileSystem: ServerFileSystem): Detected {
        val rootNames = fileSystem.list("").map { it.name }

        rootNames.firstOrNull {
            it.startsWith("fabric-server-launch") || it.startsWith("fabric-server-mc.") || it.startsWith(".fabric")
        }?.let {
            return Detected("fabric", rootNames.firstNotNullOfOrNull(::mcVersionFromFileName))
        }
        rootNames.firstOrNull { it.startsWith("quilt-server-launch") }?.let {
            return Detected("quilt", rootNames.firstNotNullOfOrNull(::mcVersionFromFileName))
        }

        listSafe(fileSystem, "libraries/net/neoforged/neoforge").firstOrNull { it.directory }?.let {
            return Detected("neoforge", mcVersionFromNeoForgeVersion(it.name))
        }
        listSafe(fileSystem, "libraries/net/minecraftforge/forge").firstOrNull { it.directory }?.let {
            return Detected("forge", it.name.substringBefore("-").takeIf(String::isNotBlank))
        }

        val fallbackVersion = listSafe(fileSystem, "libraries/com/mojang/minecraft")
            .firstOrNull { it.directory }?.name?.substringBefore("-")
        return Detected(modLoader = null, mcVersion = fallbackVersion)
    }

    private suspend fun listSafe(fileSystem: ServerFileSystem, relativePath: String) = try {
        fileSystem.list(relativePath)
    } catch (e: IOException) {
        emptyList()
    }

    private fun mcVersionFromFileName(name: String): String? =
        Regex("""mc\.?(\d+\.\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)

    /** NeoForge versions look like "21.1.77", mapping to Minecraft "1.21.1". */
    private fun mcVersionFromNeoForgeVersion(version: String): String? {
        val parts = version.split(".")
        if (parts.size < 2) return null
        val minor = parts[0].toIntOrNull() ?: return null
        val patch = parts[1].toIntOrNull() ?: return null
        return if (patch == 0) "1.$minor" else "1.$minor.$patch"
    }

    internal data class Detected(val modLoader: String?, val mcVersion: String?)
}
