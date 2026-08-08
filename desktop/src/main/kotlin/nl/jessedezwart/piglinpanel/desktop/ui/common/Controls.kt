package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.DropdownMenuItem as M3DropdownMenuItem
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.NavigationRailItem as M3NavigationRailItem
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.Tab as M3Tab
import androidx.compose.material3.TextButton as M3TextButton

/**
 * Material 3 controls with the desktop hand cursor baked in.
 *
 * Compose Multiplatform's Material components never set a hover cursor — the library is shared with
 * Android, where there is none — so every clickable control would otherwise keep the arrow cursor.
 * Screens import these instead of `androidx.compose.material3.*`, which keeps the cursor decision in
 * one place; call sites stay identical to the Material originals.
 *
 * Only the parameters this app actually uses are exposed; add more as needed.
 */

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) = M3Button(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    colors = colors,
    content = content,
)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3OutlinedButton(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    content = content,
)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = M3TextButton(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    content = content,
)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) = M3IconButton(
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    colors = colors,
    content = content,
)

@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) = M3FilterChip(
    selected = selected,
    onClick = onClick,
    label = label,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    leadingIcon = leadingIcon,
)

@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    colors: MenuItemColors = MenuDefaults.itemColors(),
) = M3DropdownMenuItem(
    text = text,
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    leadingIcon = leadingIcon,
    trailingIcon = trailingIcon,
    enabled = enabled,
    colors = colors,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    selectedContentColor: Color = LocalContentColor.current,
    unselectedContentColor: Color = selectedContentColor,
    interactionSource: MutableInteractionSource? = null,
) = M3Tab(
    selected = selected,
    onClick = onClick,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    text = text,
    icon = icon,
    selectedContentColor = selectedContentColor,
    unselectedContentColor = unselectedContentColor,
    interactionSource = interactionSource,
)

@Composable
fun NavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable (() -> Unit)? = null,
) = M3NavigationRailItem(
    selected = selected,
    onClick = onClick,
    icon = icon,
    modifier = modifier.handCursor(enabled),
    enabled = enabled,
    label = label,
)
