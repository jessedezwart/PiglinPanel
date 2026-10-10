package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.ModFile

class ModDependencyGraphTest {
    private fun mod(
        modId: String,
        requires: List<String> = emptyList(),
        provides: List<String> = emptyList(),
        archived: Boolean = false,
        version: String? = null,
        ranges: Map<String, String> = emptyMap(),
    ): ModFile {
        val fileName = if (archived) "$modId.jar.disabled" else "$modId.jar"
        return ModFile(
            folderId = "mods",
            folderName = "mods",
            folderPath = "mods",
            relativePath = "mods/$fileName",
            fileName = fileName,
            size = 1,
            archived = archived,
            sha1 = null,
            update = null,
            metadata = ModJarMetadata(
                modId = modId,
                version = version,
                requires = requires.map { ModDependencyRef(it, ranges[it]) },
                provides = provides,
            ),
        )
    }

    @Test
    fun reportsTheEnabledModsThatDependOnAMod() {
        val api = mod("fabric-api")
        val sodium = mod("sodium", requires = listOf("fabric-api"))
        val standalone = mod("standalone")

        val graph = ModDependencyGraph.of(listOf(api, sodium, standalone))

        assertEquals(listOf("sodium"), graph.dependentsOf(api).map { it.metadata!!.modId })
        assertEquals(emptyList(), graph.dependentsOf(standalone))
    }

    @Test
    fun listsRequiredDependenciesWithTheirProviders() {
        val api = mod("fabric-api", provides = listOf("fabric"))
        val bundler = mod("bundler", requires = listOf("fabric", "absent", "inner"), provides = listOf("inner"))

        val graph = ModDependencyGraph.of(listOf(api, bundler))

        val deps = graph.requiredDependenciesOf(bundler)
        assertEquals(listOf("fabric", "absent"), deps.map { it.id })
        assertEquals(api, deps[0].provider)
        assertEquals(null, deps[1].provider)
    }

    @Test
    fun countsADependencySatisfiedByAProvidedId() {
        val api = mod("fabric-api", provides = listOf("fabric"))
        val other = mod("other", requires = listOf("fabric"))

        val graph = ModDependencyGraph.of(listOf(api, other))

        assertEquals(listOf("other"), graph.wouldBreakIfRemoved(api).map { it.metadata!!.modId })
    }

    @Test
    fun doesNotWarnWhenASecondEnabledJarStillProvidesTheDependency() {
        val api = mod("fabric-api", provides = listOf("fabric"))
        val fork = mod("fabric-api-fork", provides = listOf("fabric"))
        val other = mod("other", requires = listOf("fabric"))

        val graph = ModDependencyGraph.of(listOf(api, fork, other))

        // Removing either one leaves the other providing `fabric`, so nothing actually breaks.
        assertEquals(emptyList(), graph.wouldBreakIfRemoved(api))
        assertEquals(emptyList(), graph.wouldBreakIfRemoved(fork))
    }

    @Test
    fun ignoresDisabledModsWhenLookingForDependents() {
        val api = mod("fabric-api")
        val disabledDependent = mod("sodium", requires = listOf("fabric-api"), archived = true)

        val graph = ModDependencyGraph.of(listOf(api, disabledDependent))

        assertEquals(emptyList(), graph.wouldBreakIfRemoved(api))
    }

    @Test
    fun resolvesDisabledDependenciesTransitively() {
        val target = mod("sodium", requires = listOf("fabric-api"), archived = true)
        val api = mod("fabric-api", requires = listOf("base"), archived = true)
        val base = mod("base", archived = true)
        val unrelated = mod("unrelated", archived = true)

        val graph = ModDependencyGraph.of(listOf(target, api, base, unrelated))

        assertEquals(
            listOf("fabric-api", "base"),
            graph.disabledDependenciesOf(target).map { it.metadata!!.modId },
        )
    }

    @Test
    fun leavesAlreadyEnabledDependenciesAlone() {
        val target = mod("sodium", requires = listOf("fabric-api"), archived = true)
        val api = mod("fabric-api")

        val graph = ModDependencyGraph.of(listOf(target, api))

        assertEquals(emptyList(), graph.disabledDependenciesOf(target))
        assertEquals(emptyList(), graph.missingDependenciesOf(target))
    }

    @Test
    fun reportsDependenciesThatAreNotInstalledAtAllAsMissing() {
        val target = mod("sodium", requires = listOf("fabric-api"))

        val graph = ModDependencyGraph.of(listOf(target))

        assertEquals(listOf("fabric-api"), graph.missingDependenciesOf(target))
        // Nothing to enable: this one needs downloading, not renaming.
        assertEquals(emptyList(), graph.disabledDependenciesOf(target))
    }

    @Test
    fun survivesACycleBetweenTwoDisabledMods() {
        val a = mod("a", requires = listOf("b"), archived = true)
        val b = mod("b", requires = listOf("a"), archived = true)

        val resolved = ModDependencyGraph.of(listOf(a, b)).disabledDependenciesOf(a)

        assertEquals(listOf("b"), resolved.map { it.metadata!!.modId })
    }

    @Test
    fun treatsJarsWithoutMetadataAsUnknownRatherThanIndependent() {
        val unreadable = mod("x").copy(metadata = null)
        val api = mod("fabric-api")

        val graph = ModDependencyGraph.of(listOf(unreadable, api))

        // It cannot depend on anything it never declared, and nothing can depend on it.
        assertTrue(graph.dependentsOf(api).isEmpty())
        assertTrue(graph.missingDependenciesOf(unreadable).isEmpty())
    }

    @Test
    fun reportsADependencyThatIsPresentButTooOld() {
        val api = mod("fabric-api", version = "0.92.2")
        val sodium = mod("sodium", requires = listOf("fabric-api"), ranges = mapOf("fabric-api" to ">=0.100.0"))

        val conflicts = ModDependencyGraph.of(listOf(api, sodium)).unsatisfiedDependenciesOf(sodium)

        assertEquals(listOf(VersionConflict("fabric-api", ">=0.100.0", "0.92.2")), conflicts)
        // The id is present, so it is not also reported as missing.
        assertEquals(emptyList(), ModDependencyGraph.of(listOf(api, sodium)).missingDependenciesOf(sodium))
    }

    @Test
    fun acceptsADependencyInsideItsRange() {
        val api = mod("fabric-api", version = "0.110.5+1.21.1")
        val sodium = mod("sodium", requires = listOf("fabric-api"), ranges = mapOf("fabric-api" to ">=0.100.0"))

        assertEquals(emptyList(), ModDependencyGraph.of(listOf(api, sodium)).unsatisfiedDependenciesOf(sodium))
    }

    @Test
    fun staysQuietWhenTheProviderOnlyBundlesTheId() {
        // The host jar's version says nothing about the version of the library inside it, so a
        // range check against it would be a guess dressed up as a failure.
        val host = mod("connector", provides = listOf("fabric-api"), version = "1.0.0")
        val sodium = mod("sodium", requires = listOf("fabric-api"), ranges = mapOf("fabric-api" to ">=0.100.0"))

        assertEquals(emptyList(), ModDependencyGraph.of(listOf(host, sodium)).unsatisfiedDependenciesOf(sodium))
    }

    @Test
    fun staysQuietWhenTheProviderDeclaresNoVersion() {
        val api = mod("fabric-api", version = null)
        val sodium = mod("sodium", requires = listOf("fabric-api"), ranges = mapOf("fabric-api" to ">=0.100.0"))

        assertEquals(emptyList(), ModDependencyGraph.of(listOf(api, sodium)).unsatisfiedDependenciesOf(sodium))
    }
}
