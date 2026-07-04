package nl.jessedezwart.piglinpanel.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.AppConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class AppConfigStoreTest {
    private val fs = FakeFileSystem()
    private val store = AppConfigStore(fs, "/config/config.json".toPath())

    @Test
    fun savesAndLoadsServerProfiles() {
        val profile = ServerProfile(name = "Test server", rootPath = "C:/server")
        val config = AppConfig(servers = listOf(profile), selectedServerId = profile.id)

        store.save(config)
        val loaded = store.load()

        assertEquals(1, loaded.servers.size)
        assertEquals("Test server", loaded.servers.first().name)
        assertEquals(profile.id, loaded.selectedServerId)
    }

    @Test
    fun returnsEmptyConfigWhenFileMissing() {
        val loaded = store.load()
        assertTrue(loaded.servers.isEmpty())
        assertEquals(null, loaded.selectedServerId)
    }

    /**
     * Migration-compat gate: a config.json written verbatim by the old Jackson-based Java app must
     * load without changes.
     */
    @Test
    fun loadsLegacyJacksonFormatConfig() {
        val legacyJson = """
            {
              "servers" : [ {
                "id" : "0b6ad2b3-3c1f-4a41-9a70-1f2ab7f4e9d1",
                "name" : "Survival",
                "accessType" : "SSH",
                "rootPath" : "/opt/minecraft",
                "logsPath" : "logs",
                "modFolders" : [ {
                  "id" : "mods",
                  "name" : "mods",
                  "path" : "mods"
                } ],
                "ssh" : {
                  "host" : "mc.example.com",
                  "port" : 2222,
                  "username" : "minecraft",
                  "password" : "hunter2",
                  "privateKeyPath" : "",
                  "privateKeyPassphrase" : ""
                },
                "rcon" : {
                  "enabled" : true,
                  "host" : "mc.example.com",
                  "port" : 25575,
                  "password" : "rconpw"
                }
              } ],
              "selectedServerId" : "0b6ad2b3-3c1f-4a41-9a70-1f2ab7f4e9d1"
            }
        """.trimIndent()
        fs.createDirectories("/config".toPath())
        fs.write("/config/config.json".toPath()) { writeUtf8(legacyJson) }

        val loaded = store.load()

        val server = loaded.servers.single()
        assertEquals("Survival", server.name)
        assertEquals(AccessType.SSH, server.accessType)
        assertEquals("/opt/minecraft", server.rootPath)
        assertEquals("mods", server.modFolders.single().path)
        assertEquals(2222, server.ssh.port)
        assertEquals("minecraft", server.ssh.username)
        assertTrue(server.rcon.enabled)
        assertEquals("rconpw", server.rcon.password)
        assertEquals(server.id, loaded.selectedServerId)

        // Round-trip: saving and re-loading must not lose data.
        store.save(loaded)
        assertEquals(loaded, store.load())
    }

    @Test
    fun toleratesUnknownJsonFields() {
        fs.createDirectories("/config".toPath())
        fs.write("/config/config.json".toPath()) {
            writeUtf8("""{"servers":[],"selectedServerId":null,"someFutureField":42}""")
        }
        assertTrue(store.load().servers.isEmpty())
    }
}
