package nl.jessedezwart.piglinpanel.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Piglin gold on a dark scheme, matching the old web UI's look. */
private val PiglinGold = Color(0xFFE0A040)
private val PiglinGoldDim = Color(0xFF8A6428)
private val DarkSurface = Color(0xFF16181D)
private val DarkBackground = Color(0xFF101216)

private val PiglinColorScheme = darkColorScheme(
    primary = PiglinGold,
    onPrimary = Color(0xFF241A08),
    primaryContainer = PiglinGoldDim,
    onPrimaryContainer = Color(0xFFFFE1B0),
    secondary = Color(0xFFB0A58F),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = Color(0xFF23262E),
)

@Composable
fun PiglinTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PiglinColorScheme, content = content)
}
