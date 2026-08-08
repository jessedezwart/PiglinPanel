package nl.jessedezwart.piglinpanel.env

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.model.ServerProfile

class ServerEnvironmentResolverTest {
    private val resolver = ServerEnvironmentResolver(ServerFileSystemProvider { error("not used directly") })

    @Test
    fun detectsFabricFromLauncherJarName() = runTest {
        val fs = FakeServerFileSystem()
        fs.put("fabric-server-mc.1.21.1-loader.0.16.9-launcher.1.0.1.jar", byteArrayOf())

        val detected = resolver.detect(fs)

        assertEquals("fabric", detected.modLoader)
        assertEquals("1.21.1", detected.mcVersion)
    }

    @Test
    fun detectsQuiltFromLauncherJarName() = runTest {
        val fs = FakeServerFileSystem()
        fs.put("quilt-server-launch.jar", byteArrayOf())

        val detected = resolver.detect(fs)

        assertEquals("quilt", detected.modLoader)
        assertNull(detected.mcVersion)
    }

    @Test
    fun detectsNeoForgeFromLibraryVersionDirectory() = runTest {
        val fs = FakeServerFileSystem()
        fs.put("libraries/net/neoforged/neoforge/21.1.77/neoforge-21.1.77.jar", byteArrayOf())

        val detected = resolver.detect(fs)

        assertEquals("neoforge", detected.modLoader)
        assertEquals("1.21.1", detected.mcVersion)
    }

    @Test
    fun detectsForgeFromLibraryVersionDirectory() = runTest {
        val fs = FakeServerFileSystem()
        fs.put("libraries/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0.jar", byteArrayOf())

        val detected = resolver.detect(fs)

        assertEquals("forge", detected.modLoader)
        assertEquals("1.20.1", detected.mcVersion)
    }

    @Test
    fun leavesBothNullForVanillaServer() = runTest {
        val fs = FakeServerFileSystem()
        fs.put("server.jar", byteArrayOf())

        val detected = resolver.detect(fs)

        assertNull(detected.modLoader)
        assertNull(detected.mcVersion)
    }

    @Test
    fun applyNeverClobbersExistingValueWithNull() {
        val profile = ServerProfile(modLoader = "fabric", mcVersion = "1.20.4")

        val result = resolver.apply(profile, ServerEnvironmentResolver.Detected(modLoader = null, mcVersion = null))

        assertEquals("fabric", result.modLoader)
        assertEquals("1.20.4", result.mcVersion)
    }

    @Test
    fun applyOverwritesWithFreshlyDetectedValues() {
        val profile = ServerProfile(modLoader = "fabric", mcVersion = "1.20.4")

        val result = resolver.apply(profile, ServerEnvironmentResolver.Detected(modLoader = "neoforge", mcVersion = "1.21.1"))

        assertEquals("neoforge", result.modLoader)
        assertEquals("1.21.1", result.mcVersion)
    }
}
