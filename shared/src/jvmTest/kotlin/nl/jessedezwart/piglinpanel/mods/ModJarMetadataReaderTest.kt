package nl.jessedezwart.piglinpanel.mods

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModJarMetadataReaderTest {
    private fun jar(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // A real mod jar has classes in it too; the reader must find the manifest among them.
            zip.putNextEntry(ZipEntry("net/example/Mod.class"))
            zip.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            zip.closeEntry()
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun readsFabricIdNameAndRequiredDependencies() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "fabric.mod.json" to """
                {
                  "schemaVersion": 1,
                  "id": "sodium",
                  "name": "Sodium",
                  "version": "0.5.8",
                  "depends": { "minecraft": "~1.21", "java": ">=21", "fabricloader": ">=0.15", "fabric-api": "*" },
                  "suggests": { "iris": "*" }
                }
                """.trimIndent(),
            ),
        )!!

        assertEquals("sodium", metadata.modId)
        assertEquals("Sodium", metadata.name)
        assertEquals("0.5.8", metadata.version)
        // minecraft/java/fabricloader come from the platform, not from a jar in the mods folder.
        assertEquals(listOf("fabric-api"), metadata.requires)
        assertEquals(listOf("iris"), metadata.optional)
    }

    @Test
    fun treatsProvidedIdsAsSatisfyingDependencies() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "fabric.mod.json" to """
                { "id": "fabric-api", "name": "Fabric API", "provides": ["fabric"], "depends": {} }
                """.trimIndent(),
            ),
        )!!

        assertEquals(listOf("fabric"), metadata.provides)
        assertTrue("fabric" in metadata.ids() && "fabric-api" in metadata.ids())
    }

    @Test
    fun readsQuiltDependenciesFromBothStringAndObjectForms() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "quilt.mod.json" to """
                {
                  "quilt_loader": {
                    "id": "examplemod",
                    "version": "1.0.0",
                    "metadata": { "name": "Example Mod" },
                    "depends": [
                      "quilt_base",
                      { "id": "quilted_fabric_api", "versions": "*" },
                      { "id": "modmenu", "optional": true }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )!!

        assertEquals("examplemod", metadata.modId)
        assertEquals("Example Mod", metadata.name)
        assertEquals(listOf("quilted_fabric_api"), metadata.requires)
        assertEquals(listOf("modmenu"), metadata.optional)
    }

    @Test
    fun readsForgeMandatoryAndNeoForgeTypeDependencies() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/mods.toml" to """
                modLoader = "javafml"
                loaderVersion = "[47,)"

                [[mods]]
                modId = "jei"
                version = "15.0.0"
                displayName = "Just Enough Items"

                [[dependencies.jei]]
                    modId = "forge"
                    mandatory = true
                [[dependencies.jei]]
                    modId = "curios"
                    mandatory = false
                [[dependencies.jei]]
                    modId = "patchouli"   # comment should not end up in the id
                    type = "required"
                """.trimIndent(),
            ),
        )!!

        assertEquals("jei", metadata.modId)
        assertEquals("Just Enough Items", metadata.name)
        assertEquals(listOf("patchouli"), metadata.requires)
        assertEquals(listOf("curios"), metadata.optional)
    }

    @Test
    fun prefersNeoForgeManifestWhenBothArePresent() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/mods.toml" to "[[mods]]\nmodId = \"legacy\"\n",
                "META-INF/neoforge.mods.toml" to "[[mods]]\nmodId = \"modern\"\n",
            ),
        )!!

        assertEquals("modern", metadata.modId)
    }

    /** A jar that ships other jars inside it, the way loaders' jar-in-jar mechanisms do. */
    private fun jarBundling(manifest: Pair<String, String>, vararg nested: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(manifest.first))
            zip.write(manifest.second.toByteArray())
            zip.closeEntry()
            for ((name, bytes) in nested) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun countsBundledJarsIdsAsProvided() {
        val metadata = ModJarMetadataReader.read(
            jarBundling(
                "fabric.mod.json" to """{ "id": "sable", "name": "Sable" }""",
                "META-INF/jars/sablecompanion-1.0.jar" to
                    jar("fabric.mod.json" to """{ "id": "sablecompanion" }"""),
            ),
        )!!

        assertEquals("sable", metadata.modId)
        assertTrue("sablecompanion" in metadata.ids())
    }

    @Test
    fun looksThroughNeoForgeJarJarTwoLevelsDeep() {
        // Forgified Fabric API's shape: a NeoForge jar bundling a jar that bundles the fabric modules.
        val metadata = ModJarMetadataReader.read(
            jarBundling(
                "META-INF/neoforge.mods.toml" to "[[mods]]\nmodId = \"fabric_api\"\n",
                "META-INF/jarjar/fabric-api-base.jar" to jarBundling(
                    "fabric.mod.json" to """{ "id": "fabric-api-base" }""",
                    "META-INF/jars/fabric.jar" to jar("fabric.mod.json" to """{ "id": "fabric" }"""),
                ),
            ),
        )!!

        assertEquals("fabric_api", metadata.modId)
        assertTrue("fabric" in metadata.ids(), "expected bundled fabric module, got ${metadata.ids()}")
    }

    @Test
    fun treatsEveryModDeclaredInOneTomlAsProvided() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                [[mods]]
                modId = "simulated"
                displayName = "Simulated"
                [[mods]]
                modId = "simulated_extras"

                [[dependencies.simulated_extras]]
                    modId = "simulated"
                    type = "required"
                """.trimIndent(),
            ),
        )!!

        assertEquals("simulated", metadata.modId)
        assertEquals(listOf("simulated_extras"), metadata.provides)
        // The second mod's dependency on the first is satisfied inside the same jar.
        assertEquals(emptyList(), metadata.requires)
    }

    @Test
    fun returnsNullForJarsWithoutAModManifest() {
        assertNull(ModJarMetadataReader.read(jar()))
        assertNull(ModJarMetadataReader.read("not a zip at all".toByteArray()))
        // Present but unparseable is still "unknown", never "no dependencies".
        assertNull(ModJarMetadataReader.read(jar("fabric.mod.json" to "{ broken")))
    }

    @Test
    fun readsClientSideOnlyOutOfNeoForgeToml() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                loaderVersion = "[4,)"
                clientSideOnly = true
                [[mods]]
                modId = "authme"
                displayName = "Auth Me"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.CLIENT, metadata.side)
    }

    @Test
    fun readsSideFromTheModsOwnPlatformDependency() {
        // NeoForge has no per-mod side key, so a client-only mod says so by scoping its own
        // dependency on Minecraft or the loader. This is the shape most client mods actually ship.
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader="javafml"
                [[mods]]
                   modId="badoptimizations"
                [[dependencies.badoptimizations]]
                   modId="minecraft"
                   mandatory=true
                   versionRange="[1.21.1,)"
                   side="CLIENT"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.CLIENT, metadata.side)
    }

    @Test
    fun oneSidedPlatformDependencyWinsOverABothSidedOne() {
        // Auth Me scopes neoforge to CLIENT while leaving minecraft on BOTH; the mod is client-only.
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                [[mods]]
                modId = "authme"
                [[dependencies.'authme']]
                modId = "neoforge"
                type = "required"
                side = "CLIENT"
                [[dependencies.'authme']]
                modId = "minecraft"
                type = "required"
                side = "BOTH"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.CLIENT, metadata.side)
    }

    @Test
    fun aBothSidedPlatformDependencyIsNotTakenAsAClaim() {
        // `side = "BOTH"` is what every generated toml ships with, so it says nothing about the mod
        // and must leave room for the repository to answer instead.
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                [[mods]]
                modId = "bookshelf"
                [[dependencies.bookshelf]]
                modId = "minecraft"
                type = "required"
                side = "BOTH"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.UNKNOWN, metadata.side)
    }

    @Test
    fun readsFabricEnvironment() {
        val client = ModJarMetadataReader.read(
            jar("fabric.mod.json" to """{ "id": "iris", "environment": "client" }"""),
        )!!
        val both = ModJarMetadataReader.read(
            jar("fabric.mod.json" to """{ "id": "lithium", "environment": "*" }"""),
        )!!
        val unstated = ModJarMetadataReader.read(
            jar("fabric.mod.json" to """{ "id": "carpet" }"""),
        )!!

        assertEquals(ModSide.CLIENT, client.side)
        assertEquals(ModSide.BOTH, both.side)
        assertEquals(ModSide.UNKNOWN, unstated.side)
    }

    @Test
    fun readsQuiltEnvironment() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "quilt.mod.json" to """
                {
                  "quilt_loader": { "id": "example", "version": "1.0" },
                  "minecraft": { "environment": "dedicated_server" }
                }
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.SERVER, metadata.side)
    }

    @Test
    fun aMergedJarTakesTheSideFromWhicheverManifestCommitsToOne() {
        // Build Guide ships both manifests: the NeoForge one scopes it to the client, the Fabric
        // one still carries the "runs everywhere" default it was generated with.
        val metadata = ModJarMetadataReader.read(
            jar(
                "fabric.mod.json" to """{ "id": "buildguide", "environment": "*" }""",
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                [[mods]]
                modId = "buildguide"
                [[dependencies.buildguide]]
                modId = "minecraft"
                side = "CLIENT"
                """.trimIndent(),
            ),
        )!!

        assertEquals("buildguide", metadata.modId)
        assertEquals(ModSide.CLIENT, metadata.side)
    }

    @Test
    fun readsClientSideOnlyInsideTheModsTable() {
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                [[mods]]
                    modId = "example"
                    clientSideOnly = "true"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.CLIENT, metadata.side)
    }

    @Test
    fun theModsOwnSideKeyWinsOverAStaleClientSideOnlyFlag() {
        // Distant Horizons keeps a clientSideOnly from before it grew server support, next to the
        // `side` that reflects what it does now.
        val metadata = ModJarMetadataReader.read(
            jar(
                "META-INF/neoforge.mods.toml" to """
                modLoader = "javafml"
                [[mods]]
                    modId = "distanthorizons"
                    clientSideOnly = "true"
                    side = "BOTH"
                """.trimIndent(),
            ),
        )!!

        assertEquals(ModSide.BOTH, metadata.side)
    }
}
