package nl.jessedezwart.piglinpanel.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.IOException
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class LocalServerFileSystemTest {
    private val fakeFs = FakeFileSystem()
    private val root = "/server"

    private fun fs(): LocalServerFileSystem {
        fakeFs.createDirectories(root.toPath())
        return LocalServerFileSystem(ServerProfile(rootPath = root), fakeFs)
    }

    @Test
    fun readsAndListsInsideRoot() = runTest {
        val fs = fs()
        fakeFs.write(root.toPath() / "server.properties") { writeUtf8("motd=Piglinpanel") }
        fakeFs.createDirectories(root.toPath() / "logs")

        val entries = fs.list("")
        assertEquals(listOf("logs", "server.properties"), entries.map { it.name })
        assertTrue(entries.first().directory)
        assertEquals("motd=Piglinpanel", fs.readBytes("server.properties").decodeToString())
    }

    @Test
    fun listReturnsEmptyForMissingDirectory() = runTest {
        assertEquals(emptyList(), fs().list("does-not-exist"))
    }

    @Test
    fun writeCreatesParentsAndMoveReplaces() = runTest {
        val fs = fs()
        fs.writeBytes("mods/sub/a.jar", byteArrayOf(1, 2))
        assertEquals(2, fs.readBytes("mods/sub/a.jar").size)

        fs.writeBytes("mods/b.jar", byteArrayOf(3))
        fs.move("mods/sub/a.jar", "mods/b.jar")
        assertEquals(2, fs.readBytes("mods/b.jar").size)
    }

    @Test
    fun deleteIsIdempotent() = runTest {
        val fs = fs()
        fs.delete("nope.txt")
        fs.writeBytes("yes.txt", byteArrayOf(1))
        fs.delete("yes.txt")
        assertEquals(emptyList(), fs.list("").filter { it.name == "yes.txt" })
    }

    @Test
    fun rejectsPathsOutsideRoot() = runTest {
        val failure = assertFailsWith<IOException> { fs().readBytes("../outside.txt") }
        assertTrue(failure.message!!.contains("escapes server root"))
    }
}
