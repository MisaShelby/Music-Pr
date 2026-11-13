package fr.offlinetune.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The chosen « Apparence », read everywhere colours and shapes are drawn (changes redraw the app at once). */
object LookState {
    var look by androidx.compose.runtime.mutableStateOf(fr.offlinetune.settings.Look())
    var lock by androidx.compose.runtime.mutableStateOf(fr.offlinetune.settings.LockLook())
    val palette get() = fr.offlinetune.settings.Palettes.of(look.palette)
}

/** Colours of the current palette (« Apparence › Fond »). */
object Ink {
    val Background get() = Color(LookState.palette.background)
    val Surface get() = Color(LookState.palette.surface)
    val SurfaceHigh get() = Color(LookState.palette.surfaceHigh)
    val Text get() = Color(LookState.palette.text)
    val Muted get() = Color(LookState.palette.muted)
    val Brass = Color(0xFFE8B04B)
    val Error = Color(0xFFFF8A80)
}

/** Rounded corners scaled by « Apparence › Coins ». */
fun rounded(radius: androidx.compose.ui.unit.Dp) =
    androidx.compose.foundation.shape.RoundedCornerShape(radius * LookState.look.roundness)

private val type = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** Dark theme; [accent] comes from the current cover (brass by default) unless a fixed colour is chosen. */
@Composable
fun OfflineTuneTheme(accent: Color = Ink.Brass, content: @Composable () -> Unit) {
    val look = LookState.look
    val a by animateColorAsState(if (look.accentFromCover) accent else Color(look.accent), tween(600), label = "accent")
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
    ) {
        // « Taille du texte ».
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * look.textScale),
            content = content,
        )
    }
}
