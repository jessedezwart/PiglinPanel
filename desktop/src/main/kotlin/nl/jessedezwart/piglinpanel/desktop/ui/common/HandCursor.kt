package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon

/**
 * Shows the hand cursor while hovering an interactive control. Compose Desktop keeps the default
 * arrow cursor on buttons, chips and tabs, which makes them read as non-interactive.
 *
 * Pass the control's own `enabled` flag so disabled controls keep the arrow cursor.
 */
fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this
