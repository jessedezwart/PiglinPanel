package nl.jessedezwart.piglinpanel.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Persisted application configuration. The JSON field names must stay compatible with the
 * config.json files written by the pre-Kotlin (Jackson) versions of Piglin Panel.
 */
@Serializable
data class AppConfig(
    val servers: List<ServerProfile> = emptyList(),
    val selectedServerId: String? = null,
)

@Serializable
enum class AccessType {
    LOCAL,
    SSH,
}

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ServerProfile(
    val id: String = Uuid.random().toString(),
    val name: String = "Minecraft Server",
    val accessType: AccessType = AccessType.LOCAL,
    val rootPath: String = "",
    val logsPath: String = "logs",
    val modFolders: List<ModFolder> = listOf(ModFolder("mods", "mods", "mods")),
    val ssh: SshConfig = SshConfig(),
    val rcon: RconConfig = RconConfig(),
) {
    override fun toString(): String = name
}

@Serializable
data class ModFolder(
    val id: String = "",
    val name: String = "",
    val path: String = "",
) {
    val displayName: String
        get() = name.ifBlank { path }
}

@Serializable
data class SshConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKeyPath: String = "",
    val privateKeyPassphrase: String = "",
)

@Serializable
data class RconConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 25575,
    val password: String = "",
)
