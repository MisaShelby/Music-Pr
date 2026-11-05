package fr.offlinetune.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.offlinetune.core.search.Provider
import fr.offlinetune.ui.rememberCover
import fr.offlinetune.ui.theme.Ink

object Glass {
    val Fill = Color.White.copy(alpha = 0.12f)
    val FillStrong = Color.White.copy(alpha = 0.18f)
    val Stroke = Color.White.copy(alpha = 0.20f)
    val CoralGradient = listOf(Color(0xFFFF6A88), Color(0xFFFF2D55), Color(0xFFC2185B))
}

/**
 * The layer behind every screen: the current cover, heavily blurred (real blur on Android 12+), under a dark scrim.
 * Translucent [GlassSurface]s on top of it read as frosted glass.
 */
@Composable
fun AppBackdrop(coverKey: String?, accent: Color, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val bmp = rememberCover(coverKey, 256)
    val a by animateColorAsState(accent, label = "backdrop")
    Box(modifier.fillMaxSize().background(Ink.Background)) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(80.dp),
                alpha = 0.55f,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(a.copy(alpha = 0.35f), Color.Transparent), radius = 1400f)))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.Background.copy(alpha = 0.35f), Ink.Background.copy(alpha = 0.85f)))))
        // Screens drawn on the transparent backdrop must get light text by default.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Ink.Text) { content() }
    }
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.clip(shape).background(if (strong) Glass.FillStrong else Glass.Fill).border(1.dp, Glass.Stroke, shape),
        content = content,
    )
}

@Composable
fun GlassChip(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val bg = when { selected -> Color.White; else -> Glass.Fill }
    val fg = when { selected -> Ink.Background; enabled -> Ink.Text; else -> Ink.Muted }
    Box(
        Modifier.clip(shape).background(bg).border(1.dp, if (selected) Color.Transparent else Glass.Stroke, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
}

/** Coloured rounded square with the platform's initials (no official logos). */
@Composable
fun PlatformBadge(provider: Provider, size: Dp = 18.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(Color(provider.colorHex)),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.initials, color = Color.White, fontWeight = FontWeight.Black, fontSize = (size.value * 0.42f).sp, maxLines = 1)
    }
}

data class NavItem(val label: String, val icon: ImageVector)

/** Floating glass navigation bar. */
@Composable
fun GlassNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    GlassSurface(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp), strong = true,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.clip(RoundedCornerShape(20.dp)).clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) { Icon(item.icon, null, tint = if (on) MaterialTheme.colorScheme.primary else Ink.Text) }
                    Text(item.label, style = MaterialTheme.typography.labelMedium, color = if (on) Ink.Text else Ink.Muted)
                }
            }
        }
    }
}
