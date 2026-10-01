package nl.jessedezwart.piglinpanel.desktop.update

import com.seanproctor.potassium.updater.PotassiumUpdater
import com.seanproctor.potassium.updater.UpdateInfo
import com.seanproctor.potassium.updater.UpdateResult
import com.seanproctor.potassium.updater.provider.GitHubProvider
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Where the update banner is in its life; [Idle] means there is nothing to show. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data class Available(val version: String) : UpdateState

    /** [fraction] is null while the size of the download is not known yet. */
    data class Downloading(val version: String, val fraction: Float?) : UpdateState

    data class Ready(val version: String) : UpdateState

    data class Failed(val version: String, val message: String) : UpdateState
}

/**
 * Keeps an installed copy of the app current from the GitHub releases the release workflow
 * publishes. Nothing is downloaded or installed until the user asks for it.
 */
class AppUpdater(private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(AppUpdater::class.java)

    private val updater = PotassiumUpdater {
        provider = GitHubProvider(owner = "jessedezwart", repo = "piglinpanel")
    }

    /** The running version, for display. */
    val currentVersion: String get() = updater.currentVersion

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var pending: UpdateInfo? = null
    private var installer: File? = null

    /**
     * Only an installed copy updates itself. `gradlew run` carries a version too, and would
     * otherwise offer to run the installer over a development checkout; the jpackage launcher is
     * what sets this property.
     */
    private val installed = System.getProperty("jpackage.app-path") != null

    fun checkForUpdates() {
        if (!installed || !updater.isUpdateSupported()) return
        if (_state.value != UpdateState.Idle) return
        scope.launch {
            when (val result = updater.checkForUpdates()) {
                is UpdateResult.Available -> {
                    pending = result.info
                    log.info("Update available: {} (running {})", result.info.version, currentVersion)
                    _state.value = UpdateState.Available(result.info.version)
                }
                UpdateResult.NotAvailable -> log.info("Piglin Panel {} is up to date", currentVersion)
                // Being offline is not worth a banner; the next start checks again.
                is UpdateResult.Error -> log.warn("Update check failed: {}", result.exception.message)
            }
        }
    }

    fun download() {
        val info = pending ?: return
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(info.version, fraction = null)
        scope.launch {
            try {
                updater.downloadUpdate(info).collect { progress ->
                    val file = progress.file
                    if (file != null) {
                        installer = file
                        _state.value = UpdateState.Ready(info.version)
                    } else {
                        val fraction = progress.totalBytes.takeIf { it > 0 }
                            ?.let { (progress.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f) }
                        _state.value = UpdateState.Downloading(info.version, fraction)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Update download failed", e)
                _state.value = UpdateState.Failed(info.version, e.message ?: "Download failed")
            }
        }
    }

    /** Runs the downloaded installer and relaunches. This ends the process and does not return. */
    fun installAndRestart() {
        val file = installer ?: return
        updater.installAndRestart(file)
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }
}
