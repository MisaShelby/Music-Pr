package fr.offlinetune.ui.library

import fr.offlinetune.ui.theme.rounded
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.playlist.Playlist
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.player.NewPlaylistDialog
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Playlist I/O can fail (SD card removed, permission revoked, name taken): show it, never crash. */
fun CoroutineScope.safely(context: android.content.Context, block: suspend () -> Unit) = launch {
    try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
        val msg = (e as? fr.offlinetune.core.model.OfflineTuneException)?.userMessage() ?: e.message ?: "Erreur"
        android.widget.Toast.makeText(context, "Liste : $msg", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
fun PlaylistsTab(app: App, onOpen: (Playlist) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlists by app.library.playlists.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Nouvelle liste", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (playlists.isEmpty()) item {
            Text(
                "Vos listes sont enregistrées en fichiers .m3u8 dans le dossier Playlists de votre musique : Poweramp et VLC les voient aussi.",
                color = Ink.Muted, modifier = Modifier.padding(20.dp),
            )
        }
        items(playlists, key = { it.file.uri }) { p ->
            val first = p.playable.firstOrNull()
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(first?.let { Covers.key(it.coverUri, it.uri) }, rounded(12.dp), Modifier.size(56.dp), size = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.items.size} titre${if (p.items.size > 1) "s" else ""}", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
            }
        }
    }
    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false }) { name ->
            creating = false
            scope.safely(context) { app.library.createPlaylist(name) }
        }
    }
}

@Composable
fun PlaylistDetail(app: App, uri: String, onGone: () -> Unit, onRenamed: (fr.offlinetune.library.PlaylistFile) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val playlists by app.library.playlists.collectAsState()
    val current by app.player.state.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val p = playlists.firstOrNull { it.file.uri == uri } ?: run {
        Text("Liste introuvable.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
        return
    }
    val playable = p.playable

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { app.player.playList(playable.map { it.playable() }, 0) }, enabled = playable.isNotEmpty()) {
                Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Lire")
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { app.player.playList(playable.shuffled().map { it.playable() }, 0) }, enabled = playable.isNotEmpty()) {
                Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Aléatoire")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Renommer") }
            IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.Delete, "Supprimer la liste") }
        }
        if (p.items.isEmpty()) {
            Text("Liste vide. Ajoutez des titres par un appui long dans la bibliothèque, depuis l'écran de lecture ou au téléchargement.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(p.items) { i, item ->
                val t = item.track
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = t != null) {
                        // Queue = this track and the ones after it in the list.
                        val idx = playable.indexOf(t).coerceAtLeast(0)
                        app.player.playList(fr.offlinetune.core.library.LibrarySort.queueFrom(playable, idx).map { it.playable() }, 0)
                    }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverImage(t?.let { Covers.key(it.coverUri, it.uri) }, rounded(8.dp), Modifier.size(44.dp), size = 128)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t?.title ?: (item.entry.label?.ifBlank { null } ?: item.entry.path.substringAfterLast('/')),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                            color = when { t == null -> Ink.Muted; current.mediaId == t.uri -> MaterialTheme.colorScheme.primary; else -> Ink.Text },
                        )
                        Text(t?.artist ?: "Fichier introuvable", maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                    }
                    IconButton(onClick = { scope.safely(context) { app.library.movePlaylistItem(p, i, i - 1) } }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Monter") }
                    IconButton(onClick = { scope.safely(context) { app.library.movePlaylistItem(p, i, i + 1) } }, enabled = i < p.items.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Descendre") }
                    IconButton(onClick = { scope.safely(context) { app.library.removeFromPlaylist(p, i) } }) { Icon(Icons.Rounded.Close, "Retirer de la liste") }
                }
            }
        }
    }

    if (renaming) {
        NewPlaylistDialog(title = "Renommer la liste", initial = p.name, confirm = "Renommer", onDismiss = { renaming = false }) { name ->
            renaming = false
            scope.safely(context) { onRenamed(app.library.renamePlaylist(p, name)) }
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("La liste et son fichier .m3u8 seront supprimés. Les morceaux restent sur le téléphone.") },
            confirmButton = { TextButton(onClick = { deleting = false; scope.safely(context) { app.library.deletePlaylist(p); onGone() } }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Annuler") } },
        )
    }
}
