package fr.offlinetune.ui.sound

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.dsp.DspSettings
import fr.offlinetune.core.dsp.Presets
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.ui.glass.Glass
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val TREBLE_COLOR = Color(0xFF4DA3FF)

private fun formatDb(v: Float): String = "%.1f".format(v).replace('.', ',').let { if (v > 0.04f) "+$it" else it }

/** Son: console layout — tall EQ faders with a preamp column, curve preview, switches, preset, and knobs. */
@Composable
fun SoundScreen(padding: PaddingValues) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val dsp = settings.dsp
    fun update(change: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(change) }
    fun updateDsp(custom: Boolean = false, change: (DspSettings) -> DspSettings) =
        update { s -> s.copy(dsp = change(s.dsp), currentPresetName = if (custom) "Personnalisé" else s.currentPresetName) }

    var page by rememberSaveable { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        // Segmented top bar: Égaliseur · Tonalité · Effets
        GlassSurface(Modifier.fillMaxWidth().padding(top = 12.dp), RoundedCornerShape(28.dp), strong = true) {
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(Icons.Rounded.Equalizer to "Égaliseur", Icons.Rounded.Tune to "Tonalité", Icons.Rounded.GraphicEq to "Effets")
                    .forEachIndexed { i, (icon, label) ->
                        Row(
                            Modifier.clip(RoundedCornerShape(22.dp)).background(if (page == i) Glass.FillStrong else Color.Transparent)
                                .clickable { page = i }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, label, tint = if (page == i) Ink.Text else Ink.Muted)
                            if (page == i) { Spacer(Modifier.width(8.dp)); Text(label, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (page) {
            0 -> {
                // Faders
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    GlassSurface(Modifier.width(70.dp), RoundedCornerShape(26.dp)) {
                        Column(Modifier.padding(vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Fader(dsp.preampDb, enabled = dsp.enabled, color = Ink.Text) { v -> updateDsp { it.copy(preampDb = v) } }
                            Text("Préampli", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Text(formatDb(dsp.preampDb), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        DspSettings.LABELS.forEachIndexed { i, label ->
                            Column(Modifier.width(50.dp).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val v = dsp.bands.getOrElse(i) { 0f }
                                Fader(v, enabled = dsp.enabled && dsp.eqEnabled, color = accent) { nv ->
                                    updateDsp(custom = true) { d -> d.copy(bands = d.bands.copyOf().also { it[i] = nv }) }
                                }
                                Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(formatDb(v), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                CurvePreview(dsp, accent)
                Spacer(Modifier.height(12.dp))

                // Switches + preset + menu
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Égal.", on = dsp.eqEnabled) { updateDsp { it.copy(eqEnabled = !it.eqEnabled) } }
                    Spacer(Modifier.width(10.dp))
                    PresetButton(settings, Modifier.weight(1f), onPick = { name, values ->
                        update { s -> s.copy(dsp = s.dsp.copy(bands = values.copyOf()), currentPresetName = name) }
                    })
                    Spacer(Modifier.width(6.dp))
                    MoreMenu(
                        canDelete = settings.currentPresetName in settings.userPresets,
                        onSave = { saving = true },
                        onDelete = { update { s -> s.copy(userPresets = s.userPresets - s.currentPresetName, currentPresetName = "Personnalisé") } },
                        onReset = { resetting = true },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill("Ton.", on = dsp.toneEnabled) { updateDsp { it.copy(toneEnabled = !it.toneEnabled) } }
                        Pill("Limiteur", on = dsp.limiter) { updateDsp { it.copy(limiter = !it.limiter) } }
                    }
                    Spacer(Modifier.weight(1f))
                    Knob("Basses", dsp.bassDb, -12f..12f, formatDb(dsp.bassDb) + " dB", dsp.enabled && dsp.toneEnabled, size = 104.dp, color = Color(0xFF2EE59D)) { v -> updateDsp { it.copy(bassDb = v) } }
                    Spacer(Modifier.width(14.dp))
                    Knob("Aigus", dsp.trebleDb, -12f..12f, formatDb(dsp.trebleDb) + " dB", dsp.enabled && dsp.toneEnabled, size = 104.dp, color = TREBLE_COLOR) { v -> updateDsp { it.copy(trebleDb = v) } }
                }
            }
            1 -> {
                Text("Tonalité", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Basses", dsp.bassDb, -12f..12f, formatDb(dsp.bassDb) + " dB", dsp.enabled && dsp.toneEnabled, size = 130.dp, color = Color(0xFF2EE59D)) { v -> updateDsp { it.copy(bassDb = v) } }
                    Knob("Aigus", dsp.trebleDb, -12f..12f, formatDb(dsp.trebleDb) + " dB", dsp.enabled && dsp.toneEnabled, size = 130.dp, color = TREBLE_COLOR) { v -> updateDsp { it.copy(trebleDb = v) } }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Knob("Préampli", dsp.preampDb, -12f..12f, formatDb(dsp.preampDb) + " dB", dsp.enabled, size = 130.dp) { v -> updateDsp { it.copy(preampDb = v) } }
                    Knob(
                        "Balance", dsp.balance, -1f..1f,
                        when { dsp.balance < -0.01f -> "G ${(-dsp.balance * 100).roundToInt()} %"; dsp.balance > 0.01f -> "D ${(dsp.balance * 100).roundToInt()} %"; else -> "Centre" },
                        dsp.enabled, size = 130.dp, step = 0.05f,
                    ) { v -> updateDsp { it.copy(balance = if (abs(v) < 0.04f) 0f else v) } }
                }
                Text("Glissez pour tourner, touchez deux fois pour remettre à zéro.", style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                    modifier = Modifier.fillMaxWidth().padding(12.dp), textAlign = TextAlign.Center)
            }
            else -> {
                Text("Effets", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(8.dp))
                ToggleRow("Son activé", "Coupe tout le traitement de l'app d'un coup.", dsp.enabled) { v -> updateDsp { it.copy(enabled = v) } }
                ToggleRow("Mono", "Mélange gauche et droite sur les deux côtés.", dsp.mono, dsp.enabled) { v -> updateDsp { it.copy(mono = v) } }
                ToggleRow("Protection contre la saturation", "Limiteur doux quand le son dépasse −1 dB.", dsp.limiter, dsp.enabled) { v -> updateDsp { it.copy(limiter = v) } }
                TextButton(onClick = { resetting = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Réinitialiser tous les réglages du son") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Enregistrer le préréglage") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nom") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && name.trim() !in Presets.builtIn, onClick = {
                    val n = name.trim()
                    update { s -> s.copy(userPresets = s.userPresets + (n to s.dsp.bands.toList()), currentPresetName = n) }
                    saving = false
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Annuler") } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("Réinitialiser le son ?") },
            text = { Text("Égaliseur, préampli, basses, aigus, balance, mono et limiteur reviennent aux valeurs d'origine. Vos préréglages enregistrés sont conservés.") },
            confirmButton = {
                TextButton(onClick = {
                    resetting = false
                    update { it.copy(dsp = DspSettings(), currentPresetName = "Plat") }
                }) { Text("Réinitialiser") }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Annuler") } },
        )
    }
}

/** Tall fader: round handle, glowing fill from the centre line; drag to change, double tap to reset. */
@Composable
private fun Fader(value: Float, enabled: Boolean, color: Color, height: Dp = 300.dp, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(value)
    var startValue by remember { mutableFloatStateOf(0f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.width(44.dp).height(height)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(onDragStart = { startValue = current; dragged = 0f }) { change, dy ->
                    change.consume()
                    dragged += dy
                    val usable = size.height - 64.dp.toPx()
                    val raw = (startValue - dragged / usable * 24f).coerceIn(-12f, 12f)
                    val stepped = (raw * 2).roundToInt() / 2f
                    if (stepped != current) onChange(stepped)
                }
            }
            .pointerInput(enabled) { if (enabled) detectTapGestures(onDoubleTap = { onChange(0f) }) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 32.dp.toPx()
            val top = pad; val bottom = size.height - pad
            val cx = size.width / 2
            val y = { v: Float -> bottom - (v + 12f) / 24f * (bottom - top) }
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
            listOf(12f, -12f).forEach { m -> drawLine(Ink.Muted.copy(alpha = 0.5f), Offset(4.dp.toPx(), y(m)), Offset(size.width - 4.dp.toPx(), y(m)), 1.dp.toPx(), pathEffect = dash) }
            drawLine(Color.White.copy(alpha = 0.18f), Offset(cx, top), Offset(cx, bottom), 3.dp.toPx(), StrokeCap.Round)
            val c = if (enabled) color else Ink.Muted
            val vy = y(value); val zy = y(0f)
            if (abs(value) > 0.01f) {
                drawLine(c.copy(alpha = 0.25f), Offset(cx, zy), Offset(cx, vy), 10.dp.toPx(), StrokeCap.Round) // glow
                drawLine(c, Offset(cx, zy), Offset(cx, vy), 4.dp.toPx(), StrokeCap.Round)
            }
            val hw = 34.dp.toPx(); val hh = 52.dp.toPx()
            drawRoundRect(Color(0xFF23262F), Offset(cx - hw / 2, vy - hh / 2), Size(hw, hh), CornerRadius(hw / 2, hw / 2))
            drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(cx - hw / 2, vy - hh / 2), Size(hw, hh), CornerRadius(hw / 2, hw / 2), style = Stroke(1.5.dp.toPx()))
            drawLine(Color.White.copy(alpha = 0.85f), Offset(cx - 9.dp.toPx(), vy), Offset(cx + 9.dp.toPx(), vy), 3.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Response preview: smooth curve through the band gains (plus shelves), centre line = 0 dB. */
@Composable
private fun CurvePreview(dsp: DspSettings, accent: Color) {
    GlassSurface(Modifier.fillMaxWidth().height(86.dp), RoundedCornerShape(24.dp)) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            val mid = size.height / 2
            drawLine(Ink.Muted.copy(alpha = 0.4f), Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            val n = DspSettings.LABELS.size
            val gains = List(n) { i ->
                var g = if (dsp.eqEnabled) dsp.bands.getOrElse(i) { 0f } else 0f
                if (dsp.toneEnabled) { if (i <= 2) g += dsp.bassDb * (1f - i / 3f); if (i >= 8) g += dsp.trebleDb * ((i - 7) / 2f) }
                g + dsp.preampDb
            }
            val pts = gains.mapIndexed { i, g -> Offset(size.width * i / (n - 1), mid - (g.coerceIn(-18f, 18f) / 18f) * mid) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val p0 = pts[i - 1]; val p1 = pts[i]; val cxm = (p0.x + p1.x) / 2
                    cubicTo(cxm, p0.y, cxm, p1.y, p1.x, p1.y)
                }
            }
            val col = if (dsp.enabled) accent else Ink.Muted
            drawPath(path, col.copy(alpha = 0.25f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, col, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) Glass.FillStrong else Color.Transparent)
            .border(1.dp, if (on) Color.Transparent else Glass.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 12.dp),
    ) { Text(label, fontWeight = FontWeight.Bold, color = if (on) Ink.Text else Ink.Muted) }
}

@Composable
private fun PresetButton(settings: AppSettings, modifier: Modifier, onPick: (String, FloatArray) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(1.5.dp, Ink.Text.copy(alpha = 0.7f), RoundedCornerShape(50))
                .clickable { open = true }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text(settings.currentPresetName.takeIf { it != "Plat" } ?: "Pré-réglage", fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Presets.builtIn.forEach { (name, values) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(name, values) }) }
            settings.userPresets.forEach { (name, values) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(name, values.toFloatArray()) }) }
        }
    }
}

@Composable
private fun MoreMenu(canDelete: Boolean, onSave: () -> Unit, onDelete: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(48.dp).clip(CircleShape).border(1.5.dp, Ink.Text.copy(alpha = 0.7f), CircleShape).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.MoreVert, "Plus d'options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Enregistrer comme préréglage…") }, onClick = { open = false; onSave() })
            if (canDelete) DropdownMenuItem(text = { Text("Supprimer ce préréglage") }, onClick = { open = false; onDelete() })
            DropdownMenuItem(text = { Text("Réinitialiser le son") }, onClick = { open = false; onReset() })
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

