package fr.offlinetune.ui.download

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.model.SourceCandidate
import fr.offlinetune.ui.BytesImage
import fr.offlinetune.ui.theme.Ink

@Composable
fun SheetScreen(padding: PaddingValues, edit: SheetEdit, model: DownloadModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val sheet = edit.sheet
    val info = edit.edited
    var choosingSource by remember { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) model.setCoverFrom(context.applicationContext, uri)
    }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            Text("Fiche du morceau", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text("Depuis ${sheet.origin.label}", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        }
        if (edit.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                BytesImage(
                    sheet.coverBytes, RoundedCornerShape(18.dp),
                    Modifier.size(220.dp).clickable {
                        pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Ink.Background.copy(alpha = 0.75f)).padding(6.dp),
                ) { Icon(Icons.Rounded.Edit, "Changer la pochette", Modifier.size(18.dp)) }
            }
            Text(
                "Touchez la pochette pour la remplacer par une image de la galerie.",
                style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 12.dp),
            )

            if (sheet.warnings.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    sheet.warnings.forEach { w ->
                        Row {
                            Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(w, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Field("Titre", info.title) { v -> model.edit { it.copy(title = v) } }
            Field("Artiste", info.artist) { v -> model.edit { it.copy(artist = v) } }
            Field("Album", info.album ?: "") { v -> model.edit { it.copy(album = v.ifBlank { null }) } }
            Field("Artiste de l'album", info.albumArtist ?: "") { v -> model.edit { it.copy(albumArtist = v.ifBlank { null }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("Année", info.year?.toString() ?: "", Modifier.weight(1f), number = true) { v -> model.edit { it.copy(year = v.toIntOrNull()) } }
                Field("N° de piste", info.trackNumber?.toString() ?: "", Modifier.weight(1f), number = true) { v -> model.edit { it.copy(trackNumber = v.toIntOrNull()) } }
            }
            Field("Genre", info.genre ?: "") { v -> model.edit { it.copy(genre = v.ifBlank { null }) } }

            Section("Source audio") {
                val src = sheet.source
                if (src == null) {
                    Text("Aucune source choisie : rien ne sera téléchargé.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("${src.title} — ${src.artist}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(src.url, style = MaterialTheme.typography.labelMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                sheet.format?.let { Text("Qualité obtenue : ${it.label()}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
                TextButton(onClick = { choosingSource = true }, contentPadding = PaddingValues(0.dp)) { Text("Changer de source") }
            }

            Section("Paroles") {
                val lyrics = sheet.lyrics
                if (lyrics == null) {
                    Text("Aucune parole trouvée : pas de fichier .lrc.", color = Ink.Muted)
                } else {
                    Text(if (lyrics.synced != null) "Paroles synchronisées" else "Paroles non synchronisées", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                    LrcFormat.preview(lyrics, 4).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Ne pas inclure les paroles", modifier = Modifier.weight(1f))
                        Switch(checked = !edit.includeLyrics, onCheckedChange = { model.setIncludeLyrics(!it) })
                    }
                }
                TextButton(onClick = { model.refreshLyrics() }, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Rechercher à nouveau")
                }
            }
            Section("Ajouter à une playlist") {
                val playlists by (context.applicationContext as fr.offlinetune.App).library.playlists.collectAsState()
                if (playlists.isEmpty()) {
                    Text("Aucune liste. Créez-en une depuis la Bibliothèque › Playlists.", color = Ink.Muted)
                }
                playlists.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { model.togglePlaylist(p.file.uri) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = p.file.uri in edit.playlistUris, onCheckedChange = { model.togglePlaylist(p.file.uri) })
                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = { model.download() },
            enabled = sheet.source != null && edit.busy == null && info.title.isNotBlank() && info.artist.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Text("Télécharger", style = MaterialTheme.typography.titleMedium) }
    }

    if (choosingSource) {
        SourceDialog(sheet.candidates, sheet.source, onDismiss = { choosingSource = false }) { url ->
            choosingSource = false
            model.changeSource(url)
        }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), number: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        shape = RoundedCornerShape(12.dp), modifier = modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(6.dp))
    content()
}

@Composable
private fun SourceDialog(candidates: List<SourceCandidate>, current: SourceCandidate?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choisir la source audio") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c.url) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = c.url == current?.url, onClick = { onPick(c.url) })
                        Column(Modifier.weight(1f)) {
                            Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            val dur = c.durationSec?.let { "%d:%02d".format(it / 60, it % 60) } ?: "durée inconnue"
                            Text("${c.artist} — $dur", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                            c.rejectedReason?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    label = { Text("Ou collez un lien YouTube / SoundCloud") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(link) }, enabled = link.isNotBlank()) { Text("Utiliser ce lien") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
