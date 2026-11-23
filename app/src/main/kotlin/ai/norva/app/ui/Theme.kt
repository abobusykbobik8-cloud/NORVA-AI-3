package ai.norva.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val NorvaRed = Color(0xFFE5173F)
val NorvaBlue = Color(0xFF2F5BFF)

private val Scheme = darkColorScheme(
    primary = NorvaBlue,
    onPrimary = Color.White,
    secondary = NorvaRed,
    onSecondary = Color.White,
    background = Color(0xFF070A12),
    onBackground = Color(0xFFEAEFFF),
    surface = Color(0xFF0E1424),
    onSurface = Color(0xFFEAEFFF),
    surfaceVariant = Color(0xFF151C30),
    onSurfaceVariant = Color(0xFFB8C2E0),
    error = Color(0xFFFF6B6B),
)

@Composable
fun NorvaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1433), Color(0xFF070A12)))),
        content = content,
    )
}

/**
 * Стеклоподобная панель: полупрозрачная заливка и светлая окантовка.
 * Размытия содержимого под панелью (настоящего liquid glass) здесь пока нет, оно будет добавлено отдельным этапом.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    tint: Color = Color.White,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.16f), tint.copy(alpha = 0.06f))))
            .border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))),
                shape,
            ),
        content = content,
    )
}
