package fr.offlinetune.ui.library

import fr.offlinetune.ui.theme.rounded
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Handle on the right edge of a long list: drag it (or touch the edge) to jump anywhere at once; a bubble shows
 * the letter of the song reached. Dim while idle, bright while the list moves or the handle is held.
 */
@Composable
fun FastScroller(
    firstVisible: Int,
    visibleCount: Int,
    total: Int,
    scrolling: Boolean,
    scrollTo: suspend (Int) -> Unit,
    label: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    if (total <= visibleCount + 5) return
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var target by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(target) { target?.let { scrollTo(it) } }

    // Stays bright a moment after the list stops.
    var recentlyActive by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragFraction) {
        if (scrolling || dragFraction != null) recentlyActive = true
        else { delay(1500); recentlyActive = false }
    }
    val alpha by animateFloatAsState(if (recentlyActive) 1f else 0.4f, tween(250), label = "fastScroll")

    val maxFirst = (total - visibleCount).coerceAtLeast(1)
    val fraction = dragFraction ?: (firstVisible.toFloat() / maxFirst).coerceIn(0f, 1f)
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxHeight().width(44.dp).padding(vertical = 8.dp)) {
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { 52.dp.toPx() }
        fun fromY(y: Float): Float = ((y - thumbPx / 2) / (trackPx - thumbPx)).coerceIn(0f, 1f)
        fun jump(f: Float) { dragFraction = f; target = (f * maxFirst).roundToInt() }

        Box(
            Modifier.matchParentSize()
                .pointerInput(maxFirst) { detectTapGestures { o -> jump(fromY(o.y)); dragFraction = null } }
                .pointerInput(maxFirst) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> jump(fromY(o.y)) },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                    ) { change, _ -> change.consume(); jump(fromY(change.position.y)) }
                },
        )
        val thumbY = (fraction * (trackPx - thumbPx)).roundToInt()
        // The handle.
        Box(
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY) }.padding(end = 6.dp)
                .graphicsLayer { this.alpha = alpha }
                .size(width = 8.dp, height = 52.dp).clip(rounded(4.dp))
                .background(if (dragFraction != null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f)),
        )
        // Letter bubble while dragging.
        val held = dragFraction
        if (held != null) {
            val text = label((held * maxFirst).roundToInt().coerceIn(0, total - 1))
            if (!text.isNullOrBlank()) Box(
                Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(with(density) { (-56).dp.roundToPx() }, (thumbY + thumbPx / 2 - with(density) { 30.dp.toPx() }).roundToInt()) }
                    .size(60.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** First letter shown in the bubble: « A »…« Z », « # » for digits and symbols. */
fun scrollLetter(title: String): String {
    val c = java.text.Normalizer.normalize(title.trim(), java.text.Normalizer.Form.NFD).firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}
