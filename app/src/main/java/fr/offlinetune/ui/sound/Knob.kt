package fr.offlinetune.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.offlinetune.ui.theme.Ink
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Round DJ-style knob that follows the finger like a real one: turning around it clockwise raises the value,
 * anticlockwise lowers it (one full sweep of the arc = the whole range). Close to the centre, where the angle
 * is unreliable, a plain drag works instead (up/right = more). Double tap resets to [default].
 * The arc shows the value from the centre (bipolar ranges) or from the minimum.
 */
@Composable
fun Knob(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    enabled: Boolean = true,
    default: Float = 0f,
    size: Dp = 92.dp,
    step: Float = 0.5f,
    color: Color? = null,
    onChange: (Float) -> Unit,
) {
    val accent = color ?: MaterialTheme.colorScheme.primary
    val current by rememberUpdatedState(value)
    var acc by remember { mutableFloatStateOf(0f) }
    val span = range.endInclusive - range.start
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val centre = Offset(this.size.width / 2f, this.size.height / 2f)
                    val minRadius = this.size.width * 0.18f
                    detectDragGestures(onDragStart = { acc = 0f }) { change, drag ->
                        change.consume()
                        val now = change.position - centre
                        val before = now - drag
                        val delta = if (now.getDistance() > minRadius && before.getDistance() > minRadius) {
                            // Angle turned by the finger around the centre (screen y points down: clockwise = positive).
                            var a = atan2(now.y, now.x) - atan2(before.y, before.x)
                            if (a > PI) a -= (2 * PI).toFloat()
                            if (a < -PI) a += (2 * PI).toFloat()
                            a / ARC_RADIANS * span
                        } else {
                            (drag.x - drag.y) / 200f * span
                        }
                        acc += delta
                        val raw = (current + acc).coerceIn(range.start, range.endInclusive)
                        val stepped = (Math.round(raw / step) * step).coerceIn(range.start, range.endInclusive)
                        if (stepped != current) { acc = raw - stepped; onChange(stepped) }
                        // Stuck at a stop: do not store turns beyond it (turning back reacts at once).
                        if (raw <= range.start || raw >= range.endInclusive) acc = raw - stepped
                    }
                }
                .pointerInput(enabled) { if (enabled) detectTapGestures(onDoubleTap = { onChange(default) }) },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(size)) {
                val stroke = 7.dp.toPx()
                val r = this.size.minDimension / 2 - stroke
                val tl = Offset(center.x - r, center.y - r)
                val arcSize = Size(r * 2, r * 2)
                val startAngle = 135f; val sweepAll = 270f
                drawArc(Ink.SurfaceHigh, startAngle, sweepAll, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val frac = (value - range.start) / span
                val zero = if (range.start < 0 && range.endInclusive > 0) (0 - range.start) / span else 0f
                val from = startAngle + sweepAll * minOf(zero, frac)
                val sweep = sweepAll * kotlin.math.abs(frac - zero)
                if (enabled) drawArc(accent, from, sweep, false, tl, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                // The knob body, like a DJ mixer cap.
                val body = r - stroke * 1.4f
                drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3F52), Color(0xFF1A1D28)), center, body), body, center)
                drawCircle(Color.White.copy(alpha = 0.08f), body, center, style = Stroke(1.dp.toPx()))
                val angle = Math.toRadians((startAngle + sweepAll * frac).toDouble())
                val tip = Offset(center.x + (body * 0.78f) * cos(angle).toFloat(), center.y + (body * 0.78f) * sin(angle).toFloat())
                val inner = Offset(center.x + (body * 0.35f) * cos(angle).toFloat(), center.y + (body * 0.35f) * sin(angle).toFloat())
                drawLine(if (enabled) accent else Ink.Muted, inner, tip, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(display, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (enabled) Ink.Text else Ink.Muted)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
    }
}

/** The arc of the knob covers 270°. */
private const val ARC_RADIANS = (270.0 * PI / 180.0).toFloat()
