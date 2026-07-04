package nl.jessedezwart.piglinpanel.desktop.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import nl.jessedezwart.piglinpanel.config.AppConfigStore
import nl.jessedezwart.piglinpanel.config.ServerRegistry
import nl.jessedezwart.piglinpanel.config.piglinpanelConfigDir
import nl.jessedezwart.piglinpanel.fs.DefaultServerFileSystemProvider
import nl.jessedezwart.piglinpanel.fs.FileService
import nl.jessedezwart.piglinpanel.fs.ServerFileSystemProvider
import nl.jessedezwart.piglinpanel.logs.LogService
import nl.jessedezwart.piglinpanel.modrinth.ModrinthClient
import nl.jessedezwart.piglinpanel.modrinth.createHttpClient
import nl.jessedezwart.piglinpanel.mods.ModHasher
import nl.jessedezwart.piglinpanel.mods.ModService
import nl.jessedezwart.piglinpanel.nbt.QuerzNbtCodec
import nl.jessedezwart.piglinpanel.rcon.RconConfigResolver
import nl.jessedezwart.piglinpanel.rcon.RconService
import nl.jessedezwart.piglinpanel.status.ServerStatusService
import nl.jessedezwart.piglinpanel.sysinfo.SystemInfoService
import okio.FileSystem

/** Manual constructor wiring for the whole application; replaces the Spring context. */
class AppGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val fileSystems: ServerFileSystemProvider = DefaultServerFileSystemProvider

    private val configStore = AppConfigStore(
        fileSystem = FileSystem.SYSTEM,
        configPath = piglinpanelConfigDir() / "config.json",
    )
    private val rconConfigResolver = RconConfigResolver(fileSystems)

    val registry = ServerRegistry(configStore, rconConfigResolver)

    private val httpClient = createHttpClient()

    val modrinthClient = ModrinthClient(httpClient)
    val modService = ModService(fileSystems, modrinthClient, ModHasher())
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
