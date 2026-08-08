package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.jessedezwart.piglinpanel.search.ModSource

/** Brand colours of the two repositories, used to mark where a mod or update came from. */
private val MODRINTH_GREEN = Color(0xFF1BD96A)
private val CURSEFORGE_ORANGE = Color(0xFFF16436)
private val BADGE_TEXT = Color(0xFF15181C)

/** A small brand-coloured badge identifying the repository an update was resolved from. */
@Composable
fun ModSourceBadge(source: ModSource, modifier: Modifier = Modifier) {
    val (color, label) = when (source) {
        ModSource.MODRINTH -> MODRINTH_GREEN to "M"
        ModSource.CURSEFORGE -> CURSEFORGE_ORANGE to "CF"
    }
    Box(
        modifier = modifier.size(16.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = BADGE_TEXT,
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (label.length > 1) 7.sp else 9.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}
