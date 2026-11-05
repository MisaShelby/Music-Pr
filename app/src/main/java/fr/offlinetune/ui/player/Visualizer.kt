package fr.offlinetune.ui.player

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
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


/** Bars visible on each side of the centre of the globe. */
private const val DRUM_HALF = 24f

/** One bar for about 0.7 s of music, so the globe turns at a steady pace whatever the song's length. */
fun drumBarsFor(durationMs: Long): Int = (durationMs / 700).toInt().coerceIn(90, 600)

/** Playback position that advances every frame (instead of jumping twice a second), so the globe turns smoothly. */
@Composable
fun rememberSmoothProgress(positionMs: Long, durationMs: Long, playing: Boolean, speed: Float): Float {
    var p by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(positionMs, durationMs, playing, speed) {
        val dur = durationMs.coerceAtLeast(1).toFloat()
        p = (positionMs / dur).coerceIn(0f, 1f)
        if (!playing) return@LaunchedEffect
        val t0 = withFrameNanos { it }
        while (isActive) withFrameNanos { now -> p = ((positionMs + (now - t0) / 1e6f * speed) / dur).coerceIn(0f, 1f) }
    }
    return p
}

/**
 * The song's waveform wrapped around a turning globe: the current moment sits in the middle, bars slide
 * towards the edges and get thinner and dimmer as they go round. Drag to turn it (it keeps spinning a little
 * when you let go), or tap a bar to roll to it.
 */
@Composable
fun WaveDrum(
    bars: FloatArray?, progress: Float, onDrag: (Float) -> Unit, onRelease: () -> Unit, modifier: Modifier = Modifier,
    half: Float = DRUM_HALF, playedColor: Color? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val anim = remember { Animatable(0f) }
    val current by rememberUpdatedState(progress)
    val count by rememberUpdatedState(bars?.size ?: 200)
    val drag by rememberUpdatedState(onDrag)
    val release by rememberUpdatedState(onRelease)
    val quarter = (PI / 2).toFloat()

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o ->
                    val r = size.width / 2f
                    val angle = asin(((o.x - r) / r).coerceIn(-1f, 1f))
                    val target = (current + angle / quarter * half / count).coerceIn(0f, 1f)
                    scope.launch {
                        anim.snapTo(current)
                        anim.animateTo(target, tween(520, easing = FastOutSlowInEasing)) { drag(value) }
                        release()
                    }
                })
            }
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                var pos = 0f
                fun perPx() = half / quarter / (size.width / 2f) / count
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); pos = current; scope.launch { anim.stop() }; drag(pos) },
                    onDragEnd = {
                        val v = -tracker.calculateVelocity().x * perPx()
                        scope.launch {
                            anim.snapTo(pos)
                            anim.updateBounds(0f, 1f)
                            anim.animateDecay(v, exponentialDecay(frictionMultiplier = 2.2f)) { drag(value) }
                            anim.updateBounds(null, null)
                            release()
                        }
                    },
                    onDragCancel = { release() },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    pos = (pos - dx * perPx()).coerceIn(0f, 1f)
                    drag(pos)
                }
            },
    ) {
        val n = bars?.size ?: 200
        val c = progress * n
        val r = size.width / 2f
        val mid = size.height / 2
        val baseW = (quarter * r / half) * 0.62f
        val lo = bars?.minOrNull() ?: 0f
        val span = ((bars?.maxOrNull() ?: 1f) - lo).coerceAtLeast(0.05f)
        val playedBottom = lerpColor(accent, Color.White, 0.35f)
        val from = floor(c - half).toInt() - 1
        val to = ceil(c + half).toInt() + 1
        for (i in from..to) {
            if (i < 0 || i >= n) continue
            val angle = (i + 0.5f - c) / half * quarter
            if (abs(angle) >= quarter) continue
            val depth = cos(angle) // 1 in front, 0 at the edge of the globe
            val x = r + r * sin(angle)
            val w = (baseW * depth).coerceAtLeast(1.5f)
            val v = bars?.let { ((it[i] - lo) / span).coerceIn(0f, 1f) } ?: 0f
            val h = ((0.42f + 0.58f * v) * size.height * (0.78f + 0.22f * depth)).coerceAtLeast(w)
            val top = mid - h / 2
            val alpha = 0.18f + 0.82f * depth
            if (i + 0.5f <= c && playedColor != null) drawRoundRect(
                playedColor.copy(alpha = alpha), Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2, w / 2),
            ) else if (i + 0.5f <= c) drawRoundRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.97f * alpha), playedBottom.copy(alpha = 0.97f * alpha)), startY = top, endY = top + h),
                Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2, w / 2),
            ) else drawRoundRect(Color.White.copy(alpha = 0.42f * alpha), Offset(x - w / 2, top), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

/** Groups [bars] into [n] bars (loudest of each group). */
fun regroup(bars: FloatArray, n: Int): FloatArray = FloatArray(n) { i ->
    val from = (i.toLong() * bars.size / n).toInt()
    val to = ((i + 1L) * bars.size / n).toInt().coerceAtLeast(from + 1).coerceAtMost(bars.size)
    var m = 0f
    for (k in from until to) m = maxOf(m, bars[k])
    m
}

/**
 * The song as « spectrum-centered-wide » bars (quiet parts low, loud parts high) used as the seek bar:
 * played part coloured, the rest faint. Tap or slide on it to move in the song when [seekable].
 */
@Composable
fun WaveSeekBar(
    bars: FloatArray?, progress: Float, modifier: Modifier = Modifier, count: Int = 30, seekable: Boolean = true,
    onDrag: (Float) -> Unit = {}, onRelease: () -> Unit = {},
) {
    val shown = remember(bars, count) { bars?.takeIf { it.isNotEmpty() }?.let { regroup(it, count) } }
    Canvas(if (seekable) modifier.seekGestures(onDrag, onRelease) else modifier) {
        val n = shown?.size ?: count
        val slot = size.width / n
        val w = slot * 0.62f
        val lo = shown?.minOrNull() ?: 0f
        val span = ((shown?.maxOrNull() ?: 1f) - lo).coerceAtLeast(0.05f)
        for (i in 0 until n) {
            val v = shown?.let { ((it[i] - lo) / span).coerceIn(0f, 1f) } ?: 0f
            val h = ((0.16f + 0.84f * v) * size.height).coerceAtLeast(w)
            val x = i * slot + (slot - w) / 2
            val played = (i + 0.5f) / n <= progress
            drawRoundRect(
                if (played) SpectrumBlue else Color.White.copy(alpha = 0.22f),
                Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2),
            )
        }
    }
}
