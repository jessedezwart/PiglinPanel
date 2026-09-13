package nl.jessedezwart.piglinpanel.desktop.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nl.jessedezwart.piglinpanel.mods.ModSide

private val CLIENT_BLUE = Color(0xFF5B9BF8)
private val SERVER_AMBER = Color(0xFFE0A040)

/**
 * Marks a mod that runs on one side only.
 *
 * Nothing is drawn for [ModSide.BOTH] or [ModSide.UNKNOWN]: those cover almost every row, and a
 * badge repeated on every row is a column of noise rather than a signal. The absence of a badge
 * therefore means "nothing worth saying", not "runs on both".
 */
@Composable
fun ModSideBadge(side: ModSide, modifier: Modifier = Modifier) {
    val (color, label) = when (side) {
        ModSide.CLIENT -> CLIENT_BLUE to "client"
        ModSide.SERVER -> SERVER_AMBER to "server"
        ModSide.BOTH, ModSide.UNKNOWN -> return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
