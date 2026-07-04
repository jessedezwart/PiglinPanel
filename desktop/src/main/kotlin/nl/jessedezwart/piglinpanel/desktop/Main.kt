package nl.jessedezwart.piglinpanel.desktop

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
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
            g.scope.launch { g.registry.refreshSelectedRcon() }
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
    var windowVisible by remember { mutableStateOf(true) }

    Tray(
        icon = remember { ColorPainter(Color(0xFFE0A040)) },
        tooltip = "Piglin Panel",
        onAction = { windowVisible = true },
        menu = {
            Item("Open", onClick = { windowVisible = true })
            Item(
                "Exit",
                onClick = {
                    graph.shutdown()
                    exitApplication()
                },
            )
        },
    )

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Piglin Panel",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        PiglinTheme {
            Surface { App(graph) }
        }
    }
}
