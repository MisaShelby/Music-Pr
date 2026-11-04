package fr.offlinetune.ui.player

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.dsp.Waveform
import fr.offlinetune.playback.AudioAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the track's waveform is shown in the player (menu of the « onde » button). */
enum class VizMode(val label: String) {
    OFF("Visualisation désactivée"),
    TRANSPARENT("Transparence des contrôles"),
    FULL("Plein écran");

    companion object {
        /** Older saved values (BARS, CLASSIC…) map onto the new modes. */
        fun from(saved: String): VizMode = when (saved) {
            "OFF", "CLASSIC" -> OFF
            "FULL" -> FULL
            else -> TRANSPARENT
        }
    }
}

const val WAVE_BARS = 46

/** Static waveform of the whole track, computed once per song in the background (cached by the analyzer). */
@Composable
fun rememberWaveform(context: Context, uri: String?, durationMs: Long, bars: Int = WAVE_BARS): FloatArray? {
    val value by produceState<FloatArray?>(null, uri, durationMs > 0, bars) {
        value = null
        if (uri == null || durationMs <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            val window = (durationMs / (bars * 6)).coerceAtLeast(60)
            AudioAnalyzer.envelope(context, uri, 0, durationMs, window)?.let { Waveform.bars(it, bars) }
        }
    }
    return value
}

/** Tap or drag horizontally anywhere on [this] to seek (fraction 0..1). */
fun Modifier.seekGestures(onDrag: (Float) -> Unit, onRelease: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onPress = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)); tryAwaitRelease(); onRelease() })
    }
    .pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)) },
            onDragEnd = onRelease, onDragCancel = onRelease,
        ) { change, _ -> change.consume(); onDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
    }

/**
 * The song's own waveform: rounded vertical bars mirrored around the middle, the part already played bright,
 * the rest faint. Static for a given song; only the played/unplayed split moves.
 */
@Composable
fun WaveformView(bars: FloatArray?, progress: Float, modifier: Modifier = Modifier, strength: Float = 1f) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val n = bars?.size ?: WAVE_BARS
        // Thick bars with a gap a bit narrower than a bar, like a printed waveform.
        val w = (size.width / (n * 1.55f - 0.55f)).coerceAtLeast(1f)
        val gap = w * 0.55f
        // Stretch the song's own loudness range so quiet and loud parts stand apart.
        val lo = bars?.minOrNull() ?: 0f
        val span = ((bars?.maxOrNull() ?: 1f) - lo).coerceAtLeast(0.05f)
        val mid = size.height / 2
        val playedTop = Color.White.copy(alpha = 0.95f * strength)
        val playedBottom = lerpColor(accent, Color.White, 0.35f).copy(alpha = 0.95f * strength)
        val rest = Color.White.copy(alpha = 0.38f * strength)
        for (i in 0 until n) {
            val v = bars?.let { ((it[i] - lo) / span).coerceIn(0f, 1f) } ?: 0f
            val h = ((0.42f + 0.58f * v) * size.height).coerceAtLeast(w)
            val x = i * (w + gap)
            val top = mid - h / 2
            val played = (i + 0.5f) / n <= progress
            if (played) drawRoundRect(
                Brush.verticalGradient(listOf(playedTop, playedBottom), startY = top, endY = top + h),
                Offset(x, top), Size(w, h), CornerRadius(w / 2, w / 2),
            ) else drawRoundRect(rest, Offset(x, top), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** « Visualisation désactivée »: a plain thick progress bar, thicker while dragging. */
@Composable
fun ClassicSeekBar(progress: Float, dragging: Boolean, onDrag: (Float) -> Unit, onRelease: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val h by animateDpAsState(if (dragging) 20.dp else 10.dp, label = "bar")
    Box(Modifier.fillMaxWidth().height(44.dp).seekGestures(onDrag, onRelease), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), Color.White))),
            )
        }
    }
}
