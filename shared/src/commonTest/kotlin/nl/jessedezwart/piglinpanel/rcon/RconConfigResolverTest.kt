package nl.jessedezwart.piglinpanel.rcon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import nl.jessedezwart.piglinpanel.fs.FakeServerFileSystem
import nl.jessedezwart.piglinpanel.model.AccessType
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.model.SshConfig

class RconConfigResolverTest {
    private val fs = FakeServerFileSystem()
    private val resolver = RconConfigResolver({ fs })

    @Test
    fun infersEnabledRconFromServerProperties() = runTest {
        fs.put(
            "server.properties",
            "enable-rcon=true\nrcon.port=25576\nrcon.password=secret\n".encodeToByteArray(),
        )

        val refreshed = resolver.refresh(ServerProfile())!!

        assertTrue(refreshed.rcon.enabled)
        assertEquals("127.0.0.1", refreshed.rcon.host)
        assertEquals(25576, refreshed.rcon.port)
        assertEquals("secret", refreshed.rcon.password)
    }

    @Test
    fun usesSshHostAsRconHostForRemoteServers() {
        val profile = ServerProfile(
            accessType = AccessType.SSH,
            ssh = SshConfig(host = "minecraft.example.test"),
        )

        val applied = resolver.apply(profile, mapOf("enable-rcon" to "true"))

        assertTrue(applied.rcon.enabled)
        assertEquals("minecraft.example.test", applied.rcon.host)
    }

    @Test
    fun returnsNullWhenServerPropertiesUnreadable() = runTest {
        assertNull(resolver.refresh(ServerProfile()))
    }

    @Test
    fun activateEnablesRconWithAGeneratedPasswordAndLeavesTheRestOfTheFileAlone() = runTest {
        fs.put(
            "server.properties",
            "#Minecraft server properties\nenable-rcon=false\nmotd=A Server\nrcon.port=25576\n".encodeToByteArray(),
        )
        val activating = RconConfigResolver({ fs }, generatePassword = { "generated" })

        val activated = activating.activate(ServerProfile())

        assertTrue(activated.rcon.enabled)
        assertEquals(25576, activated.rcon.port)
        assertEquals("generated", activated.rcon.password)
        assertEquals(
            "#Minecraft server properties\nenable-rcon=true\nmotd=A Server\nrcon.port=25576\nrcon.password=generated\n",
            fs.files["server.properties"]!!.decodeToString(),
        )
    }

    @Test
    fun activateReplacesAnExistingPasswordAndDefaultsTheMissingPort() = runTest {
        fs.put("server.properties", "rcon.password=old".encodeToByteArray())

        val activated = resolver.activate(ServerProfile())

        assertEquals(25575, activated.rcon.port)
        assertEquals(32, activated.rcon.password.length)
        assertEquals(
            "rcon.password=${activated.rcon.password}\nenable-rcon=true\nrcon.port=25575",
            fs.files["server.properties"]!!.decodeToString(),
        )
    }

    @Test
    fun clearsPasswordWhenRconDisabled() {
        val applied = resolver.apply(
            ServerProfile(),
            mapOf("enable-rcon" to "false", "rcon.password" to "leftover"),
        )
        assertTrue(!applied.rcon.enabled)
        assertEquals("", applied.rcon.password)
    }
}
