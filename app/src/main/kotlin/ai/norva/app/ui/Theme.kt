package ai.norva.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import android.app.ActivityManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Гоночная палитра: тёмно-синий, красный, жёлтый акцент. Без логотипов и товарных знаков.
val NorvaNavy = Color(0xFF070C24)
val NorvaRed = Color(0xFFE10A3A)
val NorvaBlue = Color(0xFF2F5BFF)
val NorvaYellow = Color(0xFFFFC21A)

private val Scheme = darkColorScheme(
    primary = NorvaBlue,
    onPrimary = Color.White,
    secondary = NorvaRed,
    onSecondary = Color.White,
    tertiary = NorvaYellow,
    background = NorvaNavy,
    onBackground = Color(0xFFF2F5FF),
    surface = Color(0xFF0E1424),
    onSurface = Color(0xFFF2F5FF),
    surfaceVariant = Color(0xFF151C30),
    onSurfaceVariant = Color(0xFFB8C2E0),
    error = Color(0xFFFF6B6B),
)

private val LocalAnimateGlass = staticCompositionLocalOf { true }

/** Surface задаёт цвет содержимого: без него текст и иконки вне Material-компонентов рисовались чёрными на тёмном фоне. */
@Composable
fun NorvaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animate = remember(context) {
        val am = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        !am.isLowRamDevice && memory.totalMem > 4L * 1024 * 1024 * 1024
    }
    CompositionLocalProvider(LocalAnimateGlass provides animate) {
        MaterialTheme(colorScheme = Scheme) {
            Surface(color = Color.Transparent, contentColor = Scheme.onBackground, content = content)
        }
    }
}

/** Фон: медленно дрейфующие цветные пятна и две гоночные полосы. */
@Composable
fun AppBackground(content: @Composable BoxScope.() -> Unit) {
    val transition = if (LocalAnimateGlass.current) rememberInfiniteTransition(label = "bg") else null
    val driftX = transition?.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val driftY = transition?.animateFloat(1f, 0f, infiniteRepeatable(tween(19000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NorvaNavy)
            .drawBehind {
                val a = driftX?.value ?: 0.35f
                val b = driftY?.value ?: 0.65f
                val w = size.width
                val h = size.height
                drawRect(Brush.radialGradient(listOf(NorvaBlue.copy(alpha = 0.55f), Color.Transparent), Offset(w * (0.1f + 0.5f * a), h * (0.1f + 0.25f * b)), w * 0.95f))
                drawRect(Brush.radialGradient(listOf(NorvaRed.copy(alpha = 0.40f), Color.Transparent), Offset(w * (0.9f - 0.5f * a), h * (0.85f - 0.3f * b)), w * 0.85f))
                drawRect(Brush.radialGradient(listOf(NorvaYellow.copy(alpha = 0.14f), Color.Transparent), Offset(w * (0.8f - 0.3f * b), h * (0.3f + 0.2f * a)), w * 0.5f))
                drawLine(NorvaRed.copy(alpha = 0.55f), Offset(w * 0.62f, 0f), Offset(w * 1.02f, h * 0.16f), strokeWidth = 5f)
                drawLine(NorvaYellow.copy(alpha = 0.45f), Offset(w * 0.70f, 0f), Offset(w * 1.10f, h * 0.16f), strokeWidth = 2.5f)
            },
        content = content,
    )
}

/**
 * Стеклоподобная панель: полупрозрачная заливка, блик сверху и красно-синяя светящаяся кромка.
 * Это не размытие содержимого под панелью: библиотека Haze требует более нового Kotlin, чем закреплён в проекте.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = Color.White,
    sheen: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val top = if (tint == Color.White) 0.16f else 0.45f
    val bottom = if (tint == Color.White) 0.05f else 0.22f
    val shift = if (sheen && LocalAnimateGlass.current) {
        val t = rememberInfiniteTransition(label = "sheen")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse), label = "shift")
        v
    } else {
        0.35f
    }
    val rim = Brush.linearGradient(
        colors = listOf(NorvaRed.copy(alpha = 0.65f), Color.White.copy(alpha = 0.14f), NorvaBlue.copy(alpha = 0.65f)),
        start = Offset(shift * 400f, 0f),
        end = Offset(shift * 400f + 700f, 500f),
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = top), tint.copy(alpha = bottom))))
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent), endY = size.height * 0.4f))
            }
            .border(1.dp, rim, shape),
        content = content,
    )
}

/** Плавное сжатие при нажатии. */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.92f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

@Composable
fun RacingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(52.dp)
            .widthIn(min = 52.dp)
            .pressScale(src)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.horizontalGradient(listOf(NorvaRed, NorvaBlue)))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Переключатель режимов: цветная «жидкая» плашка плавно переезжает между пунктами. */
@Composable
fun ModeSelector(selected: Int, labels: List<String>, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(23.dp), sheen = true) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
            val seg = maxWidth / labels.size
            val x by animateDpAsState(seg * selected, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "pill")
            Box(
                Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.width(seg).fillMaxHeight()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.horizontalGradient(listOf(NorvaRed, NorvaBlue))),
            )
            Row(Modifier.fillMaxSize()) {
                labels.forEachIndexed { i, label ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(i) },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontWeight = FontWeight.Bold,
                            color = if (i == selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TypingDots(color: Color = NorvaYellow) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val s by t.animateFloat(
                0.4f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier.size(9.dp)
                    .graphicsLayer {
                        scaleX = s
                        scaleY = s
                        alpha = s
                    }
                    .background(color, CircleShape),
            )
        }
    }
}

@Composable
fun RacingProgress(fraction: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400), label = "progress")
    Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f))) {
        Box(
            Modifier.fillMaxWidth(f).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(NorvaRed, NorvaYellow))),
        )
    }
}
