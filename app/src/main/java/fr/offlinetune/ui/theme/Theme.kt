package fr.offlinetune.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

object Ink {
    val Background = Color(0xFF12141C)
    val Surface = Color(0xFF1C1F2B)
    val SurfaceHigh = Color(0xFF272B3A)
    val Text = Color(0xFFECEAE4)
    val Muted = Color(0xFF9A9DAB)
    val Brass = Color(0xFFE8B04B)
    val Error = Color(0xFFFF8A80)
}

private val type = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** Dark theme; [accent] comes from the current cover (brass by default). */
@Composable
fun OfflineTuneTheme(accent: Color = Ink.Brass, content: @Composable () -> Unit) {
    val a by animateColorAsState(accent, tween(600), label = "accent")
    val onAccent = if (a.luminance() > 0.45f) Ink.Background else Ink.Text
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = a, onPrimary = onAccent,
            secondary = a, onSecondary = onAccent,
            primaryContainer = Ink.SurfaceHigh, onPrimaryContainer = Ink.Text,
            secondaryContainer = a.copy(alpha = 0.22f), onSecondaryContainer = Ink.Text,
            background = Ink.Background, onBackground = Ink.Text,
            surface = Ink.Background, onSurface = Ink.Text,
            surfaceVariant = Ink.Surface, onSurfaceVariant = Ink.Muted,
            surfaceContainer = Ink.Surface, surfaceContainerHigh = Ink.SurfaceHigh, surfaceContainerHighest = Ink.SurfaceHigh,
            surfaceContainerLow = Ink.Surface, surfaceContainerLowest = Ink.Background,
            outline = Ink.Muted.copy(alpha = 0.5f), outlineVariant = Ink.SurfaceHigh,
            error = Ink.Error,
        ),
        typography = type,
        content = content,
    )
}
