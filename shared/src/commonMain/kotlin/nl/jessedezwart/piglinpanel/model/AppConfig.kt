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
    val curseforgeApiKey: String = "",
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
    val accessType: AccessType = AccessType.LOCAL,
    val rootPath: String = "",
    val logsPath: String = "logs",
    val modFolders: List<ModFolder> = listOf(ModFolder("mods", "mods", "mods")),
    val ssh: SshConfig = SshConfig(),
    val rcon: RconConfig = RconConfig(),
    val start: StartConfig = StartConfig(),
    /** Detected Minecraft version, e.g. "1.21.1". Null when unknown; never auto-cleared. */
    val mcVersion: String? = null,
    /** Detected mod loader: "fabric" | "forge" | "neoforge" | "quilt". Null when unknown. */
    val modLoader: String? = null,
) {
    /**
     * Label shown wherever a profile is listed: `host:/root/path` for SSH profiles, the plain root
     * path for local ones. Profiles have no user-set name — the path identifies them.
     */
    val displayName: String
        get() {
            val path = rootPath.ifBlank { "(no path)" }
            return when (accessType) {
                AccessType.LOCAL -> path
                AccessType.SSH -> ssh.host.ifBlank { "(no host)" } + ":" + path
            }
        }

    override fun toString(): String = displayName
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

/**
 * How the server process is launched. Only SSH profiles can be started from the panel; a local
 * server would need its own detachment handling, which Windows and Unix do not share.
 */
@Serializable
data class StartConfig(
    /** Shell command that starts the server, e.g. `./start.sh`. Blank means starting is off. */
    val command: String = "",
    /** Where to run it: blank means the server root; a relative path resolves against the root. */
    val workingDirectory: String = "",
    /**
     * True when [command] detaches the process itself — `screen -dmS`, `tmux new -d`,
     * `systemctl start`, `docker start`. Those run as typed; anything else is wrapped so the server
     * survives the SSH session ending.
     */
    val selfDetaching: Boolean = false,
)

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
