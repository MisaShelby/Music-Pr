package fr.offlinetune.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.settings.ACCENTS
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.settings.LOOK_PRESETS
import fr.offlinetune.settings.LockLook
import fr.offlinetune.settings.Look
import fr.offlinetune.settings.Palettes
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.currentCoverKey
import fr.offlinetune.ui.glass.AppBackdrop
import fr.offlinetune.ui.glass.GlassChip
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.player.coverPalette
import fr.offlinetune.ui.rememberCover
import fr.offlinetune.ui.theme.Ink
import fr.offlinetune.ui.theme.rounded
import kotlinx.coroutines.launch

@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun Section(title: String, hint: String? = null) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> GlassChip(label, selected = v == selected) { onPick(v) } }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Réglages › Apparence: ready-made styles, then every detail (colour, background, glass, corners, text size). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(padding: PaddingValues, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(initial = app.latest)
    val look = s.look
    fun set(change: (Look) -> Look) = scope.launch { app.settings.update { it.copy(look = change(it.look)) } }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SubHeader("Apparence", onBack)
        Text(
            "Les changements s'appliquent tout de suite à toute l'appli. Partez d'un style prêt à l'emploi, puis ajustez chaque détail.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        Spacer(Modifier.height(12.dp))
        LookPreview()

        Section("Styles prêts à l'emploi")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LOOK_PRESETS.forEach { p ->
                val on = p.look.copy(textScale = look.textScale) == look
                PresetCard(p.name, p.description, p.look, on) { set { p.look.copy(textScale = it.textScale) } }
            }
        }

        Section("Couleur principale", "Boutons, curseurs, éléments actifs.")
        SwitchRow("D'après la pochette", "La couleur change avec chaque chanson.", look.accentFromCover) { v -> set { it.copy(accentFromCover = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            ACCENTS.forEach { (name, c) ->
                val on = !look.accentFromCover && look.accent == c
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                        .border(if (on) 3.dp else 1.dp, if (on) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                        .clickable(onClickLabel = name) { set { it.copy(accentFromCover = false, accent = c) } },
                    contentAlignment = Alignment.Center,
                ) { if (on) Icon(Icons.Rounded.Check, name, tint = Color.Black.copy(alpha = 0.7f)) }
            }
        }

        Section("Couleurs de fond")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Palettes.all.forEach { p ->
                val on = look.palette == p.name
                Column(
                    Modifier.clip(rounded(14.dp)).clickable { set { it.copy(palette = p.name) } }
                        .border(if (on) 2.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f), rounded(14.dp))
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row {
                        listOf(p.background, p.surface, p.surfaceHigh).forEach { c -> Box(Modifier.size(width = 14.dp, height = 28.dp).background(Color(c))) }
                    }
                    Text(p.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Section("Arrière-plan des écrans")
        Choices(listOf("COVER" to "Pochette floutée", "GRADIENT" to "Halo de couleur", "PLAIN" to "Uni"), look.backdrop) { v -> set { it.copy(backdrop = v) } }

        Section("Effet verre", "Transparence des cartes et de la barre du bas.")
        Choices(listOf("SUBTLE" to "Discret", "NORMAL" to "Normal", "STRONG" to "Marqué"), look.glass) { v -> set { it.copy(glass = v) } }

        Section("Coins")
        Choices(listOf(0.4f to "Nets", 0.7f to "Doux", 1f to "Arrondis", 1.4f to "Très ronds"), look.roundness) { v -> set { it.copy(roundness = v) } }

        Section("Taille du texte", "${(look.textScale * 100).toInt()} %")
        Slider(look.textScale, { v -> set { it.copy(textScale = (v * 20).toInt() / 20f) } }, valueRange = 0.85f..1.3f, steps = 8)

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { set { Look() } }) { Text("Revenir au style d'origine") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PresetCard(name: String, description: String, look: Look, selected: Boolean, onClick: () -> Unit) {
    val pal = Palettes.of(look.palette)
    val accent = if (look.accentFromCover) Ink.Brass else Color(look.accent)
    Column(
        Modifier.width(150.dp).clip(rounded(18.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.15f), rounded(18.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().height(78.dp).background(
                if (look.backdrop == "PLAIN") Brush.verticalGradient(listOf(Color(pal.background), Color(pal.background)))
                else Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), Color(pal.background))),
            ),
        ) {
            Box(Modifier.align(Alignment.BottomStart).padding(10.dp).size(width = 70.dp, height = 22.dp).clip(rounded(11.dp * look.roundness)).background(Color(pal.surfaceHigh)))
            Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(26.dp).clip(CircleShape).background(accent))
            if (look.accentFromCover) Text("pochette", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(8.dp))
        }
        Column(Modifier.background(Color(pal.surface)).fillMaxWidth().padding(10.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Color(pal.text), maxLines = 1)
            Text(description, style = MaterialTheme.typography.labelMedium, color = Color(pal.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small mock-up of a screen with the current look. */
@Composable
private fun LookPreview() {
    val app = LocalContext.current.applicationContext as App
    val coverKey = currentCoverKey(app)
    val state by app.player.state.collectAsState()
    Box(Modifier.fillMaxWidth().height(190.dp).clip(rounded(22.dp)).border(1.dp, Color.White.copy(alpha = 0.15f), rounded(22.dp))) {
        AppBackdrop(coverKey, MaterialTheme.colorScheme.primary) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                Text("Bibliothèque", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                GlassSurface(Modifier.fillMaxWidth(), rounded(18.dp), strong = true) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoverImage(coverKey, rounded(10.dp), Modifier.size(44.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(state.title.ifBlank { "Titre de la chanson" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.artist.ifBlank { "Artiste" }, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1)
                        }
                        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(rounded(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(0.4f).height(5.dp).background(MaterialTheme.colorScheme.primary))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Tout", selected = true) {}
                    GlassChip("Albums", selected = false) {}
                    GlassChip("Artistes", selected = false) {}
                }
            }
        }
    }
}

/** Réglages › Écran de verrouillage: clock, widget and song layout, with a live preview. */
@Composable
fun LockLookScreen(padding: PaddingValues, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.settings.flow.collectAsState(initial = app.latest)
    val look = s.lockLook
    fun set(change: (LockLook) -> LockLook) = scope.launch { app.settings.update { it.copy(lockLook = change(it.lockLook)) } }
    fun setApp(change: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(change) }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        SubHeader("Écran de verrouillage", onBack)
        SwitchRow(
            "Afficher pendant la musique",
            "Remplace l'écran verrouillé du téléphone tant qu'une chanson joue. Glissez vers le haut pour déverrouiller.",
            s.lockScreenPlayer,
        ) { v ->
            setApp { it.copy(lockScreenPlayer = v) }
            if (v && !android.provider.Settings.canDrawOverlays(context)) {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Live preview: the real lock screen, scaled down.
        val coverKey = currentCoverKey(app)
        val bmp = rememberCover(coverKey, 256)
        val palette = remember(bmp) { coverPalette(bmp) }
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(width = 190.dp, height = 400.dp).clip(rounded(26.dp))
                .border(2.dp, Color.White.copy(alpha = 0.2f), rounded(26.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.requiredSize(width = 400.dp, height = 842.dp).graphicsLayer { scaleX = 0.475f; scaleY = 0.475f }) {
                fr.offlinetune.ui.lock.LockScreen(app, coverKey, palette, look, preview = true)
            }
        }
        Text(
            "Aperçu avec la chanson en cours.", style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
        )

        Section("Heure")
        Choices(
            listOf("THIN" to "Fine", "BOLD" to "Grasse", "DIGITAL" to "Numérique", "STACKED" to "Empilée", "HIDDEN" to "Masquée"),
            look.clock,
        ) { v -> set { it.copy(clock = v) } }
        SwitchRow("Afficher la date", null, look.showDate) { v -> set { it.copy(showDate = v) } }

        Section("Widget", "Petite information sous l'heure.")
        Choices(
            listOf("NONE" to "Aucun", "BATTERY" to "Batterie", "NEXT_SONG" to "Chanson suivante", "PROGRESS" to "Progression"),
            look.widget,
        ) { v -> set { it.copy(widget = v) } }

        Section("Chanson en lecture")
        Choices(
            listOf("LARGE" to "Grande pochette", "COMPACT" to "Carte compacte", "MINIMAL" to "Minimal", "SPECTRUM" to "Spectre plein écran"),
            look.layout,
        ) { v -> set { it.copy(layout = v) } }
        SwitchRow("Spectre animé", "Barres qui bougent avec la musique, aux couleurs de la pochette.", look.spectrum || look.layout == "SPECTRUM") { v ->
            set { it.copy(spectrum = v) }
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { set { LockLook() } }) { Text("Revenir à l'affichage d'origine") }
        Spacer(Modifier.height(32.dp))
    }
}
