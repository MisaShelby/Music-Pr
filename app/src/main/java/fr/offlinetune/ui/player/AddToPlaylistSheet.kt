package fr.offlinetune.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.library.LibTrack
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch

/** Lists the playlists; touching one adds [tracks] to it. « Nouvelle liste » creates one and adds to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(app: App, tracks: List<LibTrack>, onDone: (String?) -> Unit) {
    val playlists by app.library.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { onDone(null) }, containerColor = Ink.Surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Ajouter à une liste", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text("Nouvelle liste", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            playlists.forEach { p ->
                val already = tracks.isNotEmpty() && tracks.all { t -> p.items.any { it.track?.uri == t.uri } }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !already) {
                        scope.launch { app.library.addToPlaylist(p, tracks); onDone("Ajouté à « ${p.name} »") }
                    }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${p.items.size} titre${if (p.items.size > 1) "s" else ""}", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                    }
                    if (already) Icon(Icons.Rounded.CheckCircle, "Déjà dans la liste", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (playlists.isEmpty()) {
                Text("Aucune liste pour l'instant.", color = Ink.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
    }
    if (naming) {
        NewPlaylistDialog(onDismiss = { naming = false }) { name ->
            naming = false
            scope.launch {
                val p = app.library.createPlaylist(name)
                if (p == null) onDone("Choisissez d'abord le dossier de musique") else {
                    if (tracks.isNotEmpty()) app.library.addToPlaylist(p, tracks)
                    onDone("Ajouté à « ${p.name} »")
                }
            }
        }
    }
}

@Composable
fun NewPlaylistDialog(title: String = "Nouvelle liste", initial: String = "", confirm: String = "Créer", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nom de la liste") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
