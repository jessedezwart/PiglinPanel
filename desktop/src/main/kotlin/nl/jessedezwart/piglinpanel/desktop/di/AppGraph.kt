package nl.jessedezwart.piglinpanel.desktop.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.jessedezwart.piglinpanel.config.AppConfigStore
import nl.jessedezwart.piglinpanel.config.ServerRegistry
import nl.jessedezwart.piglinpanel.config.piglinpanelConfigDir
import nl.jessedezwart.piglinpanel.curseforge.CurseForgeClient
import nl.jessedezwart.piglinpanel.env.ServerEnvironmentResolver
import nl.jessedezwart.piglinpanel.fs.DefaultServerFileSystemProvider
import nl.jessedezwart.piglinpanel.fs.FileService
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.logs.LogService
import nl.jessedezwart.piglinpanel.modrinth.ModrinthClient
import nl.jessedezwart.piglinpanel.modrinth.createHttpClient
import nl.jessedezwart.piglinpanel.mods.ModHashStore
import nl.jessedezwart.piglinpanel.mods.ModHasher
import nl.jessedezwart.piglinpanel.mods.ModService
import nl.jessedezwart.piglinpanel.nbt.QuerzNbtCodec
import nl.jessedezwart.piglinpanel.rcon.RconConfigResolver
import nl.jessedezwart.piglinpanel.rcon.RconService
import nl.jessedezwart.piglinpanel.search.ModInstallService
import nl.jessedezwart.piglinpanel.search.ModSearchService
import nl.jessedezwart.piglinpanel.search.ModSearchSource
import nl.jessedezwart.piglinpanel.status.ServerStatusService
import nl.jessedezwart.piglinpanel.sysinfo.SystemInfoService
import okio.FileSystem

class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val fileSystems: ServerFileSystemProvider = DefaultServerFileSystemProvider

    private val configDir = piglinpanelConfigDir()

    private val configStore = AppConfigStore(
        fileSystem = FileSystem.SYSTEM,
        configPath = configDir / "config.json",
    )
    private val rconConfigResolver = RconConfigResolver(fileSystems)
    private val environmentResolver = ServerEnvironmentResolver(fileSystems)

    val registry = ServerRegistry(configStore, rconConfigResolver, environmentResolver)

    private val httpClient = createHttpClient()

    val modrinthClient = ModrinthClient(httpClient)
    val curseForgeClient = CurseForgeClient(
        http = httpClient,
        apiKey = { registry.config.value.curseforgeApiKey.takeIf { it.isNotBlank() } },
    )
    private val searchSources: () -> List<ModSearchSource> = { listOf(modrinthClient, curseForgeClient) }
    val modSearchService = ModSearchService(searchSources)
    private val modHashStore = ModHashStore(
        fileSystem = FileSystem.SYSTEM,
        path = configDir / "mod-hashes.json",
    )
    val modService = ModService(fileSystems, modrinthClient, ModHasher(modHashStore), curseForgeClient)
    val modInstallService = ModInstallService(modService, searchSources)
    val fileService = FileService(fileSystems, QuerzNbtCodec())
    val logService = LogService(fileService)
    val rconService = RconService()
    val statusService = ServerStatusService()
    val systemInfoService = SystemInfoService()

    fun shutdown() {
        scope.cancel()
        httpClient.close()
    }
}
