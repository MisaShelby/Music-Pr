package fr.offlinetune.ui.player

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.dsp.Spectrum
import fr.offlinetune.core.dsp.Waveform
import fr.offlinetune.playback.AudioAnalyzer
import fr.offlinetune.playback.LiveSpectrum
import fr.offlinetune.ui.glass.GlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

enum class PlayerVisual(val label: String) {
    BARS("Barres"), MIRROR("Miroir"), WAVE("Onde"), SPECTRUM("Spectre"), CLASSIC("Classique")
}

private const val BAR_COUNT = 56

/** Waveform of the whole track (decoded once in the background, cached by the analyzer). */
@Composable
fun rememberWaveform(context: Context, uri: String?, durationMs: Long): FloatArray? {
    val bars by produceState<FloatArray?>(null, uri, durationMs > 0) {
        value = null
        if (uri == null || durationMs <= 0) return@produceState
        value = withContext(Dispatchers.IO) {
            val window = (durationMs / 600).coerceAtLeast(100)
            AudioAnalyzer.envelope(context, uri, 0, durationMs, window)?.let { Waveform.bars(it, BAR_COUNT) }
        }
    }
    return bars
}

/** Live frequency bands of what is playing (from the app's own audio processing), smoothed for display. */
@Composable
fun rememberLiveSpectrum(active: Boolean, bands: Int = 40): FloatArray {
    var values by remember { mutableStateOf(FloatArray(bands)) }
    LaunchedEffect(active) {
        val smooth = FloatArray(bands)
        while (isActive && active) {
            val (samples, rate) = LiveSpectrum.snapshot()
            val now = if (rate > 0) Spectrum.bands(samples, rate, bands) else FloatArray(bands)
            for (i in 0 until bands) smooth[i] = if (now[i] > smooth[i]) now[i] else smooth[i] * 0.86f + now[i] * 0.14f
            values = smooth.copyOf()
            delay(33)
        }
        values = FloatArray(bands)
    }
    return values
}

/**
 * Timeline drawn as the chosen visual. Tap or drag anywhere to seek; the time shows above the finger.
 */
@Composable
fun SeekVisualizer(
    style: PlayerVisual,
    progress: Float,
    waveform: FloatArray?,
    spectrum: FloatArray,
    dragging: Boolean,
    label: String?,
    onDrag: (Float) -> Unit,
    onRelease: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Bigger: thick classic bar, tall visual styles.
    val classicH by animateDpAsState(if (dragging) 22.dp else 12.dp, label = "bar")
    val height = if (style == PlayerVisual.CLASSIC) 56.dp else 128.dp
    Box(
        Modifier.fillMaxWidth().height(height)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)); tryAwaitRelease(); onRelease() })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = onRelease, onDragCancel = onRelease,
                ) { change, _ -> change.consume(); onDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val played = Color.White
        val rest = Color.White.copy(alpha = 0.22f)
        when (style) {
            PlayerVisual.CLASSIC -> Box(Modifier.fillMaxWidth().height(classicH).clip(RoundedCornerShape(50)).background(rest)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(classicH).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), Color.White))))
            }
            PlayerVisual.BARS, PlayerVisual.MIRROR -> Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                val bars = waveform ?: FloatArray(BAR_COUNT) { 0.12f }
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (bars.size - 1)) / bars.size
                bars.forEachIndexed { i, v ->
                    val x = i * (w + gap)
                    val h = (0.08f + 0.92f * v) * (if (style == PlayerVisual.MIRROR) size.height else size.height)
                    val c = if ((i + 0.5f) / bars.size <= progress) played else rest
                    val top = if (style == PlayerVisual.MIRROR) (size.height - h) / 2 else size.height - h
                    drawRoundRect(c, Offset(x, top), Size(w, h), CornerRadius(w / 2, w / 2))
                }
            }
            PlayerVisual.WAVE -> Canvas(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                val bars = waveform ?: FloatArray(BAR_COUNT) { 0.12f }
                val mid = size.height / 2
                val step = size.width / (bars.size - 1).coerceAtLeast(1)
                fun path(sign: Float) = Path().apply {
                    bars.forEachIndexed { i, v ->
                        val p = Offset(i * step, mid - sign * v * mid * 0.95f)
                        if (i == 0) moveTo(p.x, p.y) else {
                            val prev = Offset((i - 1) * step, mid - sign * bars[i - 1] * mid * 0.95f)
                            val cx = (prev.x + p.x) / 2
                            cubicTo(cx, prev.y, cx, p.y, p.x, p.y)
                        }
                    }
                }
                val up = path(1f); val down = path(-1f)
                drawPath(up, rest, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(down, rest, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                val px = size.width * progress
                drawContext.canvas.save()
                drawContext.canvas.clipRect(0f, 0f, px, size.height)
                drawPath(up, Brush.horizontalGradient(listOf(accent, played)), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(down, Brush.horizontalGradient(listOf(accent, played)), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                drawContext.canvas.restore()
            }
            PlayerVisual.SPECTRUM -> Canvas(Modifier.fillMaxSize()) {
                val n = spectrum.size
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                val barArea = size.height
                spectrum.forEachIndexed { i, v ->
                    val h = (0.04f + 0.96f * v) * barArea
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White, accent), startY = barArea - h, endY = barArea),
                        Offset(i * (w + gap), barArea - h), Size(w, h), CornerRadius(w / 2, w / 2),
                    )
                }
            }
        }
        if (label != null) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = (maxWidth * progress - 28.dp).coerceIn(0.dp, maxWidth - 56.dp)
                GlassSurface(Modifier.padding(start = x).width(56.dp).offset(y = (-34).dp), RoundedCornerShape(50), strong = true) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
