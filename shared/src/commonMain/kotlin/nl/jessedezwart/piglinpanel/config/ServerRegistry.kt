package nl.jessedezwart.piglinpanel.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.jessedezwart.piglinpanel.env.ServerEnvironmentResolver
import nl.jessedezwart.piglinpanel.model.AppConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.rcon.RconConfigResolver

/**
 * Holds the live [AppConfig] and persists every mutation. Selecting or saving a profile refreshes
 * its RCON settings and detected mod loader/Minecraft version from the server when reachable.
 */
class ServerRegistry(
    private val store: AppConfigStore,
    private val rconConfigResolver: RconConfigResolver,
    private val environmentResolver: ServerEnvironmentResolver,
) {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config

    val servers: List<ServerProfile>
        get() = _config.value.servers

    /** Refreshes the selected profile's RCON config and detected environment on startup. */
    suspend fun refreshSelected() {
        val selected = selected() ?: return
        val refreshed = refresh(selected) ?: return
        replaceProfile(refreshed)
    }

    /** Re-runs mod loader/Minecraft version detection for [profile] and persists the result. */
    suspend fun detectEnvironment(profile: ServerProfile): ServerProfile {
        val refreshed = environmentResolver.refresh(profile) ?: profile
        replaceProfile(refreshed)
        return refreshed
    }

    /**
     * Switches RCON on in the server's `server.properties` with a generated password, and persists
     * the profile with the settings that were written. Throws when the file cannot be changed.
     */
    suspend fun activateRcon(profile: ServerProfile): ServerProfile {
        val activated = rconConfigResolver.activate(profile)
        replaceProfile(activated)
        return activated
    }

    suspend fun updateCurseforgeApiKey(key: String) {
        update { it.copy(curseforgeApiKey = key) }
    }

    private suspend fun refresh(profile: ServerProfile): ServerProfile? {
        val withRcon = rconConfigResolver.refresh(profile) ?: profile
        return environmentResolver.refresh(withRcon) ?: withRcon
    }

    fun resolve(serverId: String?): ServerProfile? {
        if (!serverId.isNullOrBlank()) {
            return servers.firstOrNull { it.id == serverId } ?: selected()
        }
        return selected()
    }

    fun selected(): ServerProfile? {
        val selectedId = _config.value.selectedServerId
        if (selectedId != null) {
            return servers.firstOrNull { it.id == selectedId }
        }
        return servers.firstOrNull()
    }

    suspend fun select(profile: ServerProfile?) {
        val refreshed = profile?.let { refresh(it) ?: it }
        if (refreshed != null) {
            replaceProfileInList(refreshed)
        }
        update { it.copy(selectedServerId = refreshed?.id) }
    }

    suspend fun save(profile: ServerProfile) {
        require(profile.id.isNotBlank()) { "Server id is required" }
        val refreshed = refresh(profile) ?: profile
        update { config ->
            val servers = config.servers.toMutableList()
            val index = servers.indexOfFirst { it.id == refreshed.id }
            if (index >= 0) {
                servers[index] = refreshed
                config.copy(servers = servers)
            } else {
                servers += refreshed
                config.copy(
                    servers = servers,
                    selectedServerId = config.selectedServerId ?: refreshed.id,
                )
            }
        }
    }

    /**
     * Records that [versionId] of the mod keyed [modKey] should not be offered as an update, or
     * clears the entry when [versionId] is null. Unlike [save], this does not touch the server:
     * nothing it changes needs re-detecting.
     */
    fun setIgnoredUpdate(profileId: String, modKey: String, versionId: String?) {
        update { config ->
            val profile = config.servers.firstOrNull { it.id == profileId } ?: return@update config
            val ignored = if (versionId == null) {
                profile.ignoredUpdates - modKey
            } else {
                profile.ignoredUpdates + (modKey to versionId)
            }
            config.replaceServer(profile.copy(ignoredUpdates = ignored))
        }
    }

    fun delete(id: String) {
        update { config ->
            config.copy(
                servers = config.servers.filterNot { it.id == id },
                selectedServerId = config.selectedServerId.takeUnless { it == id },
            )
        }
    }

    private fun replaceProfile(profile: ServerProfile) {
        update { config -> config.replaceServer(profile) }
    }

    private fun replaceProfileInList(profile: ServerProfile) {
        update { config -> config.replaceServer(profile) }
    }

    private fun AppConfig.replaceServer(profile: ServerProfile): AppConfig {
        val index = servers.indexOfFirst { it.id == profile.id }
        if (index < 0) return this
        return copy(servers = servers.toMutableList().also { it[index] = profile })
    }

    private fun update(transform: (AppConfig) -> AppConfig) {
        _config.value = transform(_config.value)
        store.save(_config.value)
    }
}
