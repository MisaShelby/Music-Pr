package fr.offlinetune.ui.sound

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.dsp.DspSettings
import fr.offlinetune.core.dsp.Presets
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SoundScreen(padding: PaddingValues) {
    val app = LocalContext.current.applicationContext as App
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val dsp = settings.dsp
    fun update(change: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(change) }
    fun updateDsp(custom: Boolean = false, change: (DspSettings) -> DspSettings) =
        update { s -> s.copy(dsp = change(s.dsp), currentPresetName = if (custom) "Personnalisé" else s.currentPresetName) }

    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Son", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            Text(if (dsp.enabled) "Activé" else "Désactivé", color = Ink.Muted, modifier = Modifier.padding(end = 8.dp))
            Switch(checked = dsp.enabled, onCheckedChange = { v -> updateDsp { it.copy(enabled = v) } })
        }

        Spacer(Modifier.height(12.dp))
        PresetPicker(settings, onPick = { name, values ->
            update { s -> s.copy(dsp = s.dsp.copy(bands = values.copyOf()), currentPresetName = name) }
        }, onDelete = { name ->
            update { s -> s.copy(userPresets = s.userPresets - name, currentPresetName = if (s.currentPresetName == name) "Personnalisé" else s.currentPresetName) }
        }, onSave = { saving = true })

        // Graphic equaliser: 10 vertical sliders.
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.Surface).padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                DspSettings.LABELS.forEachIndexed { i, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
                        val v = dsp.bands.getOrElse(i) { 0f }
                        Text(formatDb(v), style = MaterialTheme.typography.labelMedium, color = if (v == 0f) Ink.Muted else MaterialTheme.colorScheme.primary)
                        VerticalSlider(v, enabled = dsp.enabled) { nv ->
                            updateDsp(custom = true) { d -> d.copy(bands = d.bands.copyOf().also { it[i] = nv }) }
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Muted, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        LabeledSlider("Préampli", dsp.preampDb, -12f..12f, formatDb(dsp.preampDb), dsp.enabled) { v -> updateDsp { it.copy(preampDb = v) } }
        LabeledSlider("Basses", dsp.bassDb, -12f..12f, formatDb(dsp.bassDb), dsp.enabled) { v -> updateDsp { it.copy(bassDb = v) } }
        LabeledSlider("Aigus", dsp.trebleDb, -12f..12f, formatDb(dsp.trebleDb), dsp.enabled) { v -> updateDsp { it.copy(trebleDb = v) } }
        LabeledSlider(
            "Balance", dsp.balance, -1f..1f,
            when { dsp.balance < -0.01f -> "G ${(-dsp.balance * 100).roundToInt()} %"; dsp.balance > 0.01f -> "D ${(dsp.balance * 100).roundToInt()} %"; else -> "Centre" },
            dsp.enabled,
        ) { v -> updateDsp { it.copy(balance = if (kotlin.math.abs(v) < 0.04f) 0f else v) } }

        ToggleRow("Mono", "Mélange gauche et droite sur les deux côtés.", dsp.mono, dsp.enabled) { v -> updateDsp { it.copy(mono = v) } }
        ToggleRow("Protection contre la saturation", "Limiteur doux quand le son dépasse −1 dB.", dsp.limiter, dsp.enabled) { v -> updateDsp { it.copy(limiter = v) } }
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
}

private fun formatDb(v: Float): String {
    val r = (v * 10).roundToInt() / 10f
    return when { r > 0 -> "+${r.toString().removeSuffix(".0")}"; r < 0 -> r.toString().removeSuffix(".0"); else -> "0" }
}

@Composable
private fun PresetPicker(settings: AppSettings, onPick: (String, FloatArray) -> Unit, onDelete: (String) -> Unit, onSave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(settings.currentPresetName); Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Presets.builtIn.forEach { (name, values) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(name, values) })
                }
                settings.userPresets.forEach { (name, values) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(name, values.toFloatArray()) })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSave) { Text("Enregistrer…") }
        if (settings.currentPresetName in settings.userPresets) {
            TextButton(onClick = { onDelete(settings.currentPresetName) }) { Text("Supprimer") }
        }
    }
}

@Composable
private fun VerticalSlider(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Slider(
        value = value,
        onValueChange = { onChange((it * 2).roundToInt() / 2f) },
        valueRange = -12f..12f,
        enabled = enabled,
        colors = SliderDefaults.colors(inactiveTrackColor = Ink.SurfaceHigh),
        modifier = Modifier
            .graphicsLayer { rotationZ = 270f; transformOrigin = TransformOrigin(0f, 0f) }
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(placeable.height, placeable.width) { placeable.place(-placeable.width, 0) }
            }
            .width(200.dp)
            .height(30.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, enabled: Boolean, onChange: (Float) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(display, color = Ink.Muted)
    }
    Slider(
        value = value, onValueChange = { onChange(if (range.endInclusive > 2f) (it * 2).roundToInt() / 2f else it) },
        valueRange = range, enabled = enabled, colors = SliderDefaults.colors(inactiveTrackColor = Ink.SurfaceHigh),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
