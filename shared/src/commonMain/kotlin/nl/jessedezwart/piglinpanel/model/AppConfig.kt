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
    /** Detected Minecraft version, e.g. "1.21.1". Null when unknown; never auto-cleared. */
    val mcVersion: String? = null,
    /** Detected mod loader: "fabric" | "forge" | "neoforge" | "quilt". Null when unknown. */
    val modLoader: String? = null,
    /**
     * Updates the user chose to skip: mod group key to the version id that was ignored. Only that
     * version is skipped — once the repository publishes a newer one, the update is offered again.
     */
    val ignoredUpdates: Map<String, String> = emptyMap(),
    /** User-defined commands run on the server's host from Overview, e.g. `docker compose restart`. */
    val actions: List<ServerAction> = emptyList(),
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

/** A named shell command the user can run on the server's host with one click. */
@OptIn(ExperimentalUuidApi::class)
@Serializable
data class ServerAction(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val command: String = "",
    /** Where to run it: blank means the server root; a relative path resolves against the root. */
    val workingDirectory: String = "",
    /** Ask before running, for commands that interrupt players (restarts, stops). */
    val confirm: Boolean = false,
) {
    val displayName: String
        get() = name.ifBlank { command }
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
