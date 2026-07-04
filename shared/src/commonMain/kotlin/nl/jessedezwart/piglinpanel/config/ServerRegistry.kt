package nl.jessedezwart.piglinpanel.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import nl.jessedezwart.piglinpanel.model.AppConfig
import nl.jessedezwart.piglinpanel.model.ServerProfile
import nl.jessedezwart.piglinpanel.rcon.RconConfigResolver

/**
 * Holds the live [AppConfig] and persists every mutation. Selecting or saving a profile refreshes
 * its RCON settings from the server's server.properties when reachable.
 */
class ServerRegistry(
    private val store: AppConfigStore,
    private val rconConfigResolver: RconConfigResolver,
) {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config

    val servers: List<ServerProfile>
        get() = _config.value.servers

    /** Refreshes the selected profile's RCON config on startup, mirroring the old constructor. */
    suspend fun refreshSelectedRcon() {
        val selected = selected() ?: return
        val refreshed = rconConfigResolver.refresh(selected) ?: return
        replaceProfile(refreshed)
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
        val refreshed = profile?.let { rconConfigResolver.refresh(it) ?: it }
        if (refreshed != null) {
            replaceProfileInList(refreshed)
        }
        update { it.copy(selectedServerId = refreshed?.id) }
    }

    suspend fun save(profile: ServerProfile) {
        require(profile.id.isNotBlank()) { "Server id is required" }
        val refreshed = rconConfigResolver.refresh(profile) ?: profile
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
