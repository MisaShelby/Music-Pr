package fr.offlinetune.ui.download

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.link.LinkParser
import fr.offlinetune.core.link.ParsedInput
import fr.offlinetune.core.model.OfflineTuneException
import fr.offlinetune.download.DlState
import fr.offlinetune.download.DownloadItem
import fr.offlinetune.ui.theme.Ink

/** What the field contains, shown as a chip while typing. */
private fun detect(text: String): String? {
    if (text.isBlank()) return null
    return try {
        when (val p = LinkParser.parse(text)) {
            is ParsedInput.Link -> "Lien ${p.platform.label}"
            is ParsedInput.ShortLink -> "Lien ${p.platform.label}"
            is ParsedInput.Query -> "Recherche"
        }
    } catch (e: OfflineTuneException) {
        e.userMessage()
    }
}

@Composable
fun DownloadScreen(padding: PaddingValues, sharedText: String?, onSharedConsumed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val model = app.downloadModel
    val phase by model.phase.collectAsState()
    val input by model.input.collectAsState()
    val downloads by app.downloads.items.collectAsState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(sharedText) {
        if (sharedText != null) { model.submit(sharedText); onSharedConsumed() }
    }
    BackHandler(enabled = phase is DlPhase.Editing || phase is DlPhase.Sources || phase is DlPhase.Federated || phase is DlPhase.Error) { model.back() }

    val current = phase
    if (current is DlPhase.Editing) {
        SheetScreen(padding, current.edit, model, onBack = { model.back() })
        return
    }
    if (current is DlPhase.Sources) {
        SourcesScreen(padding, current.song, current.sources, onBack = { model.back() }, onChoose = { model.chooseSource(it) })
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Télécharger", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp))
            OutlinedTextField(
                value = input, onValueChange = { model.input.value = it },
                placeholder = { Text("Coller un lien ou rechercher") },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.submit() }),
                trailingIcon = {
                    if (input.isNotEmpty()) IconButton(onClick = { model.reset() }) { Icon(Icons.Rounded.Close, "Effacer") }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val chip = remember(input) { detect(input) }
                if (chip != null) AssistChip(onClick = {}, label = { Text(chip, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) model.submit(text)
                }) {
                    Icon(Icons.Rounded.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("Coller")
                }
                Spacer(Modifier.width(4.dp))
                FilledTonalButton(onClick = { keyboard?.hide(); model.submit() }, enabled = input.isNotBlank()) { Text("Valider") }
            }
        }

        when (current) {
            is DlPhase.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(current.what, color = Ink.Muted)
                }
            }
            is DlPhase.Error -> item {
                Text(current.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            }
            is DlPhase.Federated -> item { SearchResults(current.outcome, current.filter, { model.setFilter(it) }, { model.openSong(it) }) }
            else -> if (downloads.isEmpty()) item {
                Text(
                    "Collez un lien Spotify, Deezer, YouTube, YouTube Music ou SoundCloud, ou partagez-le depuis l'app d'origine. " +
                        "Vous pouvez aussi taper « artiste titre ».",
                    color = Ink.Muted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp),
                )
            }
        }

        if (downloads.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Téléchargements", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (downloads.any { it.state is DlState.Done || it.state is DlState.Failed }) {
                        TextButton(onClick = { app.downloads.clearFinished() }) { Text("Effacer les terminés") }
                    }
                }
            }
            items(downloads, key = { it.id }) { DownloadRow(it) { app.downloads.retry(it.id) } }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            when (item.state) {
                is DlState.Done -> Icon(Icons.Rounded.CheckCircle, "Terminé", tint = MaterialTheme.colorScheme.primary)
                is DlState.Failed -> Icon(Icons.Rounded.ErrorOutline, "Échec", tint = MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
        Spacer(Modifier.height(6.dp))
        when (val s = item.state) {
            DlState.Queued -> Text("En attente", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            is DlState.Running -> LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
            is DlState.Done -> Column {
                Text("Enregistré dans « ${s.folderName} »", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                s.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            is DlState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer") }
            }
        }
    }
}
