package fr.offlinetune.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import fr.offlinetune.core.dsp.Spectrum
import fr.offlinetune.playback.LiveSpectrum
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** How the bars are laid out. */
enum class SpectrumShape { CENTERED, BOTTOM, DOTS, QUADS, REFLECTED }

/**
 * Live spectrum looks. [bands] = number of bars, [fill] = share of each slot covered by the bar
 * (the rest is the gap), [round] = rounded ends.
 */
enum class SpectrumStyle(val id: String, val shape: SpectrumShape, val bands: Int, val fill: Float, val round: Boolean) {
    CENTERED("spectrum-centered", SpectrumShape.CENTERED, 32, 0.6f, true),
    CENTERED_NARROW("spectrum-centered-narrow", SpectrumShape.CENTERED, 64, 0.5f, true),
    CENTERED_WIDE("spectrum-centered-wide", SpectrumShape.CENTERED, 14, 0.68f, true),
    DOTS("spectrum-dots", SpectrumShape.DOTS, 28, 0.7f, true),
    NARROW("spectrum-narrow", SpectrumShape.BOTTOM, 110, 0.55f, false),
    PLAIN("spectrum-plain", SpectrumShape.BOTTOM, 48, 0.8f, false),
    QUADS_NARROW("spectrum-quads-narrow", SpectrumShape.QUADS, 40, 0.78f, false),
    QUADS_REFLECTED("spectrum-quads-reflected", SpectrumShape.QUADS, 24, 0.8f, false),
    REFLECTED_WIDE("spectrum-reflected-wide", SpectrumShape.REFLECTED, 20, 0.7f, true),
    WIDE("spectrum-wide", SpectrumShape.BOTTOM, 18, 0.72f, true);

    val reflected get() = this == QUADS_REFLECTED || shape == SpectrumShape.REFLECTED

    companion object {
        const val RANDOM = "RANDOM"
        /** Saved choice → style; null means « Aléatoire ». */
        fun from(saved: String?): SpectrumStyle? = entries.firstOrNull { it.name == saved }
    }
}

val SpectrumBlue = Color(0xFF9CC8FF)

/** Live frequency bands of what is playing (tapped from the app's own audio processing), smoothed for display. */
@Composable
fun rememberLiveSpectrum(active: Boolean, bands: Int): FloatArray {
    var values by remember(bands) { mutableStateOf(FloatArray(bands)) }
    LaunchedEffect(active, bands) {
        val smooth = FloatArray(bands)
        while (isActive && active) {
            val (samples, rate) = LiveSpectrum.snapshot()
            val now = if (rate > 0) Spectrum.bands(samples, rate, bands) else FloatArray(bands)
            // Fast attack, slow fall: bars jump with the beat and settle gently.
            for (i in 0 until bands) smooth[i] = if (now[i] > smooth[i]) smooth[i] * 0.35f + now[i] * 0.65f else smooth[i] * 0.84f + now[i] * 0.16f
            values = smooth.copyOf()
            delay(33)
        }
        // Let the bars fall instead of vanishing.
        while (isActive && smooth.any { it > 0.01f }) {
            for (i in 0 until bands) smooth[i] *= 0.8f
            values = smooth.copyOf()
            delay(33)
        }
        values = FloatArray(bands)
    }
    return values
}

/**
 * Up to three vivid colours taken from the cover (most present hues), brightened so the bars stand out.
 * A grey or black-and-white cover gives soft white.
 */
fun coverPalette(bmp: android.graphics.Bitmap?): List<Color> {
    if (bmp == null) return listOf(SpectrumBlue)
    val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 32, true)
    val buckets = 18
    val weight = FloatArray(buckets)
    val sumS = FloatArray(buckets)
    val sumH = FloatArray(buckets)
    val hsv = FloatArray(3)
    for (y in 0 until small.height) for (x in 0 until small.width) {
        android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
        val w = hsv[1] * hsv[2] * hsv[2]
        if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue
        val b = ((hsv[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
        weight[b] += w; sumS[b] += hsv[1] * w; sumH[b] += hsv[0] * w
    }
    if (small !== bmp) small.recycle()
    val total = weight.sum()
    if (total < 3f) return listOf(Color(0xFFF2F2F7), Color(0xFFC7C7D1))
    val picked = mutableListOf<Int>()
    for (b in weight.indices.sortedByDescending { weight[it] }) {
        if (weight[b] < total * 0.06f || picked.size == 3) break
        // Skip a hue right next to one already picked: we want distinct colours.
        if (picked.any { minOf((it - b + buckets) % buckets, (b - it + buckets) % buckets) <= 1 }) continue
        picked += b
    }
    return picked.map { b ->
        val h = sumH[b] / weight[b]
        val sat = (sumS[b] / weight[b]).coerceIn(0.45f, 0.8f)
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, 1f)))
    }.ifEmpty { listOf(SpectrumBlue) }
}

/** Colour of the bar at [t] (0 = left, 1 = right), blending the palette across the width. */
internal fun List<Color>.at(t: Float): Color {
    if (size == 1) return this[0]
    val p = t.coerceIn(0f, 1f) * (size - 1)
    val i = p.toInt().coerceAtMost(size - 2)
    val f = p - i
    val a = this[i]; val b = this[i + 1]
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f, 1f)
}

@Composable
fun SpectrumCanvas(values: FloatArray, style: SpectrumStyle, modifier: Modifier = Modifier, palette: List<Color> = listOf(SpectrumBlue), alpha: Float = 1f) {
    Canvas(modifier) { drawSpectrum(values, style, palette, alpha) }
}

private fun DrawScope.drawSpectrum(values: FloatArray, style: SpectrumStyle, palette: List<Color>, alpha: Float) {
    val n = values.size
    if (n == 0) return
    val slot = size.width / n
    val w = (slot * style.fill).coerceAtLeast(1f)
    val radius = if (style.round) CornerRadius(w / 2, w / 2) else CornerRadius.Zero
    // Bars live in the upper part when a reflection is drawn under them.
    val base = if (style.reflected) size.height * 0.68f else size.height
    for (i in 0 until n) {
        val color = palette.at((i + 0.5f) / n).copy(alpha = alpha)
        val v = values[i].coerceIn(0f, 1f)
        val x = i * slot + (slot - w) / 2
        when (style.shape) {
            SpectrumShape.CENTERED -> {
                val h = (0.03f + 0.97f * v) * size.height
                drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(w, h.coerceAtLeast(w)), radius)
            }
            SpectrumShape.BOTTOM, SpectrumShape.REFLECTED -> {
                val h = ((0.02f + 0.98f * v) * base).coerceAtLeast(if (style.round) w else 2f)
                drawRoundRect(color, Offset(x, base - h), Size(w, h), radius)
                if (style.reflected) {
                    val rh = h * 0.45f
                    drawRoundRect(
                        Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), startY = base, endY = base + rh),
                        Offset(x, base + 3f), Size(w, rh), radius,
                    )
                }
            }
            SpectrumShape.QUADS -> {
                val cell = w
                val gap = cell * 0.25f
                val count = (v * base / (cell + gap)).toInt().coerceAtLeast(1)
                for (k in 0 until count) {
                    val y = base - (k + 1) * (cell + gap) + gap
                    if (y < 0) break
                    drawRect(color, Offset(x, y), Size(cell, cell))
                    if (style.reflected) {
                        val ry = base + 3f + k * (cell + gap)
                        val a = (0.32f * (1f - k / (count + 2f))).coerceAtLeast(0f)
                        if (ry + cell <= size.height) drawRect(color.copy(alpha = a), Offset(x, ry), Size(cell, cell))
                    }
                }
            }
            SpectrumShape.DOTS -> {
                val d = w
                val gap = d * 0.35f
                val count = (v * size.height / (d + gap)).toInt().coerceAtLeast(1)
                for (k in 0 until count) {
                    val cy = size.height - k * (d + gap) - d / 2
                    if (cy < d / 2) break
                    drawCircle(color.copy(alpha = 0.55f + 0.45f * (k + 1f) / count), d / 2, Offset(x + d / 2, cy))
                }
            }
        }
    }
}
