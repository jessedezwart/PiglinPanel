package nl.jessedezwart.piglinpanel.config

import nl.jessedezwart.piglinpanel.model.AppConfig
import okio.FileSystem
import okio.Path
import kotlinx.serialization.json.Json

/**
 * Loads and saves the application config as JSON. The on-disk format must stay compatible with
 * config.json files written by the pre-Kotlin (Jackson) versions of Piglin Panel.
 */
class AppConfigStore(
    private val fileSystem: FileSystem,
    val configPath: Path,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun load(): AppConfig {
        if (!fileSystem.exists(configPath)) {
            return AppConfig()
        }
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

    fun save(config: AppConfig) {
        try {
            configPath.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(configPath) { writeUtf8(json.encodeToString(AppConfig.serializer(), config)) }
        } catch (e: okio.IOException) {
            throw IllegalStateException("Could not save app config to $configPath", e)
        }
    }
}
