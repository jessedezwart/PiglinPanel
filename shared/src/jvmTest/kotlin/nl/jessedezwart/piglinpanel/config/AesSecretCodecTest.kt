package nl.jessedezwart.piglinpanel.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.AppConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class AesSecretCodecTest {
    private val fileSystem = FakeFileSystem()
    private val keyPath = "/config/secret.key".toPath()
    private val configPath = "/config/config.json".toPath()

    @Test
    fun `round-trips a secret without storing it in the clear`() {
        val codec = AesSecretCodec(fileSystem, keyPath)

        val encrypted = codec.store(ID, "hunter2")

        assertFalse(encrypted.contains("hunter2"))
        assertEquals("hunter2", codec.read(ID, encrypted))
    }

    @Test
    fun `uses a fresh nonce per value`() {
        val codec = AesSecretCodec(fileSystem, keyPath)

        assertFalse(codec.store(ID, "hunter2") == codec.store(ID, "hunter2"))
    }

    @Test
    fun `leaves blank values alone and reads unencrypted values as-is`() {
        val codec = AesSecretCodec(fileSystem, keyPath)

        assertEquals("", codec.store(ID, ""))
        assertEquals("written-by-an-older-version", codec.read(ID, "written-by-an-older-version"))
    }

    @Test
    fun `reuses the key file across instances`() {
        val encrypted = AesSecretCodec(fileSystem, keyPath).store(ID, "hunter2")

        assertEquals("hunter2", AesSecretCodec(fileSystem, keyPath).read(ID, encrypted))
    }

    @Test
    fun `config secrets are encrypted on disk and plain in memory`() {
        val store = AppConfigStore(fileSystem, configPath, AesSecretCodec(fileSystem, keyPath))
        val config = AppConfig(
            servers = listOf(
                ServerProfile(
                    rootPath = "/srv/minecraft",
                    ssh = SshConfig(host = "mc.example.com", password = "hunter2", privateKeyPassphrase = "letmein"),
                ),
            ),
            curseforgeApiKey = "cf-key",
        )

        store.save(config)

        val onDisk = fileSystem.read(configPath) { readUtf8() }
        assertFalse(onDisk.contains("hunter2"))
        assertFalse(onDisk.contains("letmein"))
        assertFalse(onDisk.contains("cf-key"))
        assertTrue(onDisk.contains("mc.example.com"), "non-secret fields stay readable")
        assertEquals(config, store.load())
    }

    private companion object {
        const val ID = "server.abc.ssh.password"
    }
}
