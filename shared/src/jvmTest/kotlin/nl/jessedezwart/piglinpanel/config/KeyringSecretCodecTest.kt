package nl.jessedezwart.piglinpanel.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.AppConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Stands in for Credential Manager / Keychain / Secret Service. */
private class FakeCredentialStore(val failWrites: Boolean = false) : CredentialStore {
    val entries = mutableMapOf<String, String>()

    override fun get(id: String): String? = entries[id]

    override fun set(id: String, value: String) {
        if (failWrites) throw CredentialStoreException("no store today")
        entries[id] = value
    }

    override fun remove(id: String) {
        entries.remove(id)
    }
}

class KeyringSecretCodecTest {
    private val fileSystem = FakeFileSystem()
    private val configPath = "/config/config.json".toPath()

    @Test
    fun `keeps the secret in the store and only a reference in the config`() {
        val credentials = FakeCredentialStore()
        val codec = KeyringSecretCodec(credentials)

        val stored = codec.store(ID, "hunter2")

        assertEquals("keyring:v1:$ID", stored)
        assertEquals("hunter2", credentials.entries[ID])
        assertEquals("hunter2", codec.read(ID, stored))
    }

    @Test
    fun `clearing a field removes the stored credential`() {
        val credentials = FakeCredentialStore()
        val codec = KeyringSecretCodec(credentials)
        codec.store(ID, "hunter2")

        assertEquals("", codec.store(ID, ""))
        assertNull(credentials.entries[ID])
    }

    @Test
    fun `reads values written before the credential store was used`() {
        val aes = AesSecretCodec(fileSystem, "/config/secret.key".toPath())
        val codec = KeyringSecretCodec(FakeCredentialStore(), fallback = aes)

        assertEquals("hunter2", codec.read(ID, aes.store(ID, "hunter2")))
        assertEquals("plain-from-an-older-version", codec.read(ID, "plain-from-an-older-version"))
    }

    @Test
    fun `falls back when the store refuses the write`() {
        val aes = AesSecretCodec(fileSystem, "/config/secret.key".toPath())
        val codec = KeyringSecretCodec(FakeCredentialStore(failWrites = true), fallback = aes)

        val stored = codec.store(ID, "hunter2")

        assertFalse(stored.contains("hunter2"))
        assertEquals("hunter2", codec.read(ID, stored))
    }

    @Test
    fun `deleting a profile drops its credentials from the store`() {
        val credentials = FakeCredentialStore()
        val store = AppConfigStore(fileSystem, configPath, KeyringSecretCodec(credentials))
        val kept = ServerProfile(rootPath = "/srv/a", ssh = SshConfig(host = "a", password = "keep-me"))
        val removed = ServerProfile(rootPath = "/srv/b", ssh = SshConfig(host = "b", password = "drop-me"))
        store.save(AppConfig(servers = listOf(kept, removed)))

        store.save(AppConfig(servers = listOf(kept)))

        assertTrue(credentials.entries.values.contains("keep-me"))
        assertFalse(credentials.entries.values.contains("drop-me"))
    }

    @Test
    fun `config file holds no secret material`() {
        val store = AppConfigStore(fileSystem, configPath, KeyringSecretCodec(FakeCredentialStore()))
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
        assertTrue(onDisk.contains("keyring:v1:"))
        assertEquals(config, store.load())
    }

    private companion object {
        const val ID = "server.abc.ssh.password"
    }
}
