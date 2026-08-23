package nl.jessedezwart.piglinpanel.config

import nl.jessedezwart.piglinpanel.model.AppConfig
import okio.FileSystem
import okio.Path
import kotlinx.serialization.json.Json

/**
 * Loads and saves the application config as JSON. The on-disk format must stay compatible with
 * config.json files written by the pre-Kotlin (Jackson) versions of Piglin Panel.
 *
 * Secrets (SSH password and key passphrase, RCON password, API keys) pass through [secrets] on the
 * way in and out, so config.json holds either ciphertext or a reference while the rest of the app
 * keeps working with plain [AppConfig] values.
 */
class AppConfigStore(
    private val fileSystem: FileSystem,
    val configPath: Path,
    private val secrets: SecretCodec = SecretCodec.Plaintext,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): AppConfig = readStored()?.mapSecrets(secrets::read) ?: AppConfig()

    fun save(config: AppConfig) {
        val stale = staleSecretIds(config)
        try {
            configPath.parent?.let { fileSystem.createDirectories(it) }
            val stored = config.mapSecrets(secrets::store)
            fileSystem.write(configPath) { writeUtf8(json.encodeToString(AppConfig.serializer(), stored)) }
        } catch (e: okio.IOException) {
            throw IllegalStateException("Could not save app config to $configPath", e)
        }
        stale.forEach(secrets::forget)
    }

    /** The config exactly as it sits on disk, secrets still in stored form; null when there is no file yet. */
    private fun readStored(): AppConfig? {
        if (!fileSystem.exists(configPath)) return null
        val text = try {
            fileSystem.read(configPath) { readUtf8() }
        } catch (e: okio.IOException) {
            throw IllegalStateException("Could not read app config from $configPath", e)
        }
        try {
            return json.decodeFromString<AppConfig>(text)
        } catch (e: Exception) {
            throw IllegalStateException("Could not read app config from $configPath", e)
        }
    }

    /**
     * Ids that the config on disk still has but the config being saved no longer does — the
     * credentials of profiles the user has just deleted. Without this they would linger in the
     * backing store forever, since nothing else knows they existed.
     */
    private fun staleSecretIds(config: AppConfig): Set<String> {
        val previous = try {
            readStored() ?: return emptySet()
        } catch (e: IllegalStateException) {
            return emptySet()
        }
        return secretIds(previous) - secretIds(config)
    }
}

/** Applies [transform] to every field of [AppConfig] that holds a credential. */
private fun AppConfig.mapSecrets(transform: (id: String, value: String) -> String): AppConfig = copy(
    curseforgeApiKey = transform(CURSEFORGE_API_KEY_ID, curseforgeApiKey),
    servers = servers.map { server ->
        server.copy(
            ssh = server.ssh.copy(
                password = transform(sshPasswordId(server.id), server.ssh.password),
                privateKeyPassphrase = transform(sshPassphraseId(server.id), server.ssh.privateKeyPassphrase),
            ),
            rcon = server.rcon.copy(password = transform(rconPasswordId(server.id), server.rcon.password)),
        )
    },
)

private fun secretIds(config: AppConfig): Set<String> = buildSet {
    add(CURSEFORGE_API_KEY_ID)
    config.servers.forEach { server ->
        add(sshPasswordId(server.id))
        add(sshPassphraseId(server.id))
        add(rconPasswordId(server.id))
    }
}

private const val CURSEFORGE_API_KEY_ID = "curseforge.apiKey"

private fun sshPasswordId(serverId: String) = "server.$serverId.ssh.password"

private fun sshPassphraseId(serverId: String) = "server.$serverId.ssh.passphrase"

private fun rconPasswordId(serverId: String) = "server.$serverId.rcon.password"
