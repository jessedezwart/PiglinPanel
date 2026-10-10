package nl.jessedezwart.piglinpanel.mods

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.ModFile
import nl.jessedezwart.piglinpanel.model.ModGroup
import nl.jessedezwart.piglinpanel.model.ModUpdate
import nl.jessedezwart.piglinpanel.model.ServerProfile

class ModGroupFilterTest {
    private fun file(name: String, archived: Boolean = false, size: Long = 1): ModFile {
        val fileName = if (archived) "$name.jar.disabled" else "$name.jar"
        return ModFile(
            folderId = "mods",
            folderName = "mods",
            folderPath = "mods",
            relativePath = "mods/$fileName",
            fileName = fileName,
            size = size,
            archived = archived,
            sha1 = null,
            update = null,
        )
    }

    private fun group(
        name: String,
        active: Boolean = true,
        archivedFiles: List<String> = emptyList(),
        side: ModSide = ModSide.UNKNOWN,
        downloads: Long = 0,
        size: Long = 1,
        unusedLibrary: Boolean = false,
        latestVersionId: String? = null,
    ) = ModGroup(
        key = name,
        displayName = name,
        iconUrl = null,
        folderName = "mods",
        active = file(name, size = size).takeIf { active },
        archived = archivedFiles.map { file(it, archived = true, size = size) },
        update = latestVersionId?.let {
            ModUpdate(
                projectId = name,
                projectName = name,
                currentVersionId = "old",
                latestVersionId = it,
                latestVersionName = it,
                lastUpdatedAt = null,
                downloadUrl = "https://example.invalid/$name.jar",
                fileName = "$name.jar",
                iconUrl = null,
                clientSide = null,
                serverSide = null,
                downloads = downloads,
            )
        },
        side = side,
        downloads = downloads,
        unusedLibrary = unusedLibrary,
    )

    @Test
    fun `query matches display name and any file name, ignoring case and padding`() {
        val sodium = group("Sodium", archivedFiles = listOf("sodium-old"))
        assertTrue(sodium.matches(""))
        assertTrue(sodium.matches("  sod  "))
        assertTrue(sodium.matches("SODIUM-OLD"))
        assertFalse(sodium.matches("lithium"))
    }

    @Test
    fun `default filter lets everything through`() {
        val groups = listOf(group("a"), group("b", active = false, archivedFiles = listOf("b")))
        assertEquals(groups, groups.filter { ModGroupFilter().matches(it, emptySet()) })
    }

    @Test
    fun `each narrowing option keeps only its own mods`() {
        val running = group("running", side = ModSide.SERVER)
        val disabled = group("disabled", active = false, archivedFiles = listOf("disabled"))
        val unused = group("unused", unusedLibrary = true, side = ModSide.CLIENT)
        val groups = listOf(running, disabled, unused)

        fun keep(filter: ModGroupFilter, updatable: Set<String> = emptySet()) =
            groups.filter { filter.matches(it, updatable) }.map { it.key }

        assertEquals(listOf("disabled"), keep(ModGroupFilter(disabledOnly = true)))
        assertEquals(listOf("unused"), keep(ModGroupFilter(unusedLibrariesOnly = true)))
        assertEquals(listOf("running"), keep(ModGroupFilter(updatableOnly = true), setOf("running")))
        assertEquals(listOf("unused"), keep(ModGroupFilter(side = ModSide.CLIENT)))
        assertEquals(emptyList(), keep(ModGroupFilter(side = ModSide.CLIENT, disabledOnly = true)))
    }

    @Test
    fun `comparator orders by the chosen key and reverses when descending`() {
        val small = group("b", downloads = 10, size = 5)
        val large = group("A", downloads = 5, size = 50)
        val groups = listOf(small, large)

        assertEquals(listOf("A", "b"), groups.sortedWith(modGroupComparator(ModSortKey.NAME, true)).map { it.key })
        assertEquals(listOf("b", "A"), groups.sortedWith(modGroupComparator(ModSortKey.NAME, false)).map { it.key })
        assertEquals(listOf("A", "b"), groups.sortedWith(modGroupComparator(ModSortKey.DOWNLOADS, true)).map { it.key })
        assertEquals(listOf("b", "A"), groups.sortedWith(modGroupComparator(ModSortKey.SIZE, true)).map { it.key })
    }

    @Test
    fun `sorting by side puts mods with a known side first`() {
        val unknown = group("unknown")
        val server = group("server", side = ModSide.SERVER)
        val sorted = listOf(unknown, server).sortedWith(modGroupComparator(ModSortKey.SIDE, true))
        assertEquals(listOf("server", "unknown"), sorted.map { it.key })
    }

    @Test
    fun `an ignored update stays ignored only for the version that was skipped`() {
        val mod = group("mod", latestVersionId = "v2")
        assertFalse(mod.updateIgnored(ServerProfile()))
        assertTrue(mod.updateIgnored(ServerProfile(ignoredUpdates = mapOf("mod" to "v2"))))
        assertFalse(mod.updateIgnored(ServerProfile(ignoredUpdates = mapOf("mod" to "v1"))))
        assertFalse(group("plain").updateIgnored(ServerProfile(ignoredUpdates = mapOf("plain" to "v1"))))
    }
}
