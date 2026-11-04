package fr.offlinetune.ui.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.library.LibState
import fr.offlinetune.library.LibTrack
import fr.offlinetune.playback.PlayableTrack
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch

fun LibTrack.playable() = PlayableTrack(uri, title, artist, album, coverUri)

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private sealed interface Detail {
    data class Album(val name: String, val artist: String) : Detail
    data class Artist(val name: String) : Detail
    data class Playlist(val uri: String, val name: String) : Detail
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val tracks by app.library.tracks.collectAsState()
    val state by app.library.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<Detail?>(null) }

    // Scan once per app start; afterwards only with « Actualiser » (not on every tab switch).
    LaunchedEffect(Unit) { app.library.loadCache(); app.library.refreshOnce() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scope.launch {
                app.settings.update { it.copy(libraryTreeUri = uri.toString()) }
                app.library.refresh()
            }
        }
    }

    BackHandler(enabled = detail != null) { detail = null }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (detail != null) {
                IconButton(onClick = { detail = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            }
            Text(
                when (val d = detail) { is Detail.Album -> d.name; is Detail.Artist -> d.name; is Detail.Playlist -> d.name; null -> "Bibliothèque" },
                style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { app.library.refresh() } }) { Icon(Icons.Rounded.Refresh, "Actualiser") }
        }
        if (state == LibState.Scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))

        when {
            state == LibState.NeedsFolder || state is LibState.Error -> EmptyLibrary(
                (state as? LibState.Error)?.message ?: "Choisissez le dossier où se trouve votre musique sur le téléphone.",
            ) { pickFolder.launch(null) }
            detail is Detail.Playlist -> PlaylistDetail(app, (detail as Detail.Playlist).uri, onGone = { detail = null }, onRenamed = { f -> detail = Detail.Playlist(f.uri, f.name) })
            detail != null -> {
                val list = when (val d = detail!!) {
                    is Detail.Playlist -> emptyList()
                    is Detail.Album -> tracks.filter { (it.album ?: "") == d.name && (it.albumArtist ?: it.artist) == d.artist }
                        .sortedWith(compareBy<LibTrack> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title })
                    is Detail.Artist -> tracks.filter { it.artist == d.name || it.albumArtist == d.name }
                }
                TrackList(list, app)
            }
            else -> {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Rechercher un titre, un artiste, un album") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Effacer") } },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                val filtered = remember(tracks, query) {
                    val q = query.trim().lowercase()
                    if (q.isEmpty()) tracks else tracks.filter {
                        it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || (it.album?.lowercase()?.contains(q) == true)
                    }
                }
                PrimaryTabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
                    listOf("Titres", "Albums", "Artistes", "Playlists").forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                    }
                }
                if (tab == 3) {
                    PlaylistsTab(app) { p -> detail = Detail.Playlist(p.file.uri, p.name) }
                } else if (tracks.isEmpty() && state == LibState.Idle) {
                    EmptyHint("Aucun morceau trouvé dans ce dossier. Téléchargez un morceau ou choisissez un autre dossier dans Réglages.")
                } else when (tab) {
                    0 -> TrackList(filtered, app)
                    1 -> GroupList(
                        filtered.groupBy { (it.album ?: "Sans album") to (it.albumArtist ?: it.artist) }.entries.sortedBy { it.key.first.lowercase() }
                            .map { (k, v) -> Triple(k.first, "${k.second} · ${v.size} titre${if (v.size > 1) "s" else ""}", v.first()) },
                    ) { name, track -> detail = Detail.Album(name, track.albumArtist ?: track.artist) }
                    else -> GroupList(
                        filtered.groupBy { it.artist }.entries.sortedBy { it.key.lowercase() }
                            .map { (k, v) -> Triple(k, "${v.size} titre${if (v.size > 1) "s" else ""}", v.first()) },
                    ) { name, _ -> detail = Detail.Artist(name) }
                }
            }
        }
    }
}

@Composable
private fun TrackList(list: List<LibTrack>, app: App) {
    val current by app.player.state.collectAsState()
    val context = LocalContext.current
    var adding by remember { mutableStateOf<LibTrack?>(null) }
    adding?.let { t ->
        fr.offlinetune.ui.player.AddToPlaylistSheet(app, listOf(t)) { msg ->
            adding = null
            msg?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (list.size > 1) item {
            Row(
                Modifier.fillMaxWidth().clickable {
                    val shuffled = list.shuffled()
                    app.player.playList(shuffled.map { it.playable() }, 0)
                }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shuffle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Lecture aléatoire · ${list.size} titres", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
        }
        itemsIndexed(list, key = { _, t -> t.uri }) { i, t ->
            TrackRow(t, playing = current.mediaId == t.uri, onLongClick = { adding = t }) { app.player.playList(list.map { it.playable() }, i) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(t: LibTrack, playing: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(Covers.key(t.coverUri, t.uri), RoundedCornerShape(8.dp), Modifier.size(48.dp), size = 128)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
            )
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Text(formatDuration(t.durationMs), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
    }
}

@Composable
private fun GroupList(groups: List<Triple<String, String, LibTrack>>, onOpen: (String, LibTrack) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.first + it.second }) { (name, sub, sample) ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(name, sample) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(Covers.key(sample.coverUri, sample.uri), RoundedCornerShape(10.dp), Modifier.size(56.dp), size = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(message: String, onPick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Ink.Muted)
            Button(onClick = onPick) { Text("Choisir le dossier de musique") }
        }
    }
}

@Composable
private fun EmptyHint(message: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Ink.Muted)
    }
    Spacer(Modifier.height(1.dp))
}
