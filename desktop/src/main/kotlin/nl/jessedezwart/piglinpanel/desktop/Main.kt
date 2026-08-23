package nl.jessedezwart.piglinpanel.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.launch
import nl.jessedezwart.piglinpanel.desktop.di.AppGraph
import nl.jessedezwart.piglinpanel.desktop.ui.App
import nl.jessedezwart.piglinpanel.desktop.ui.theme.PiglinTheme
import org.slf4j.LoggerFactory

fun main() = application {
    val graph = remember {
        AppGraph().also { g ->
            LoggerFactory.getLogger("PiglinPanel").info("Piglin Panel starting")
            g.scope.launch { g.registry.refreshSelected() }
            // Mod icons come from Modrinth over HTTP; Coil needs the Ktor fetcher registered.
            coil3.SingletonImageLoader.setSafe { context ->
                coil3.ImageLoader.Builder(context)
                    .components {
                        add(coil3.network.ktor3.KtorNetworkFetcherFactory(nl.jessedezwart.piglinpanel.modrinth.createHttpClient()))
                    }
                    .build()
            }
        }
    }

    Window(
        onCloseRequest = {
            graph.shutdown()
            exitApplication()
        },
        title = "Piglin Panel",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        PiglinTheme {
            Surface { App(graph) }
        }
    }
}
