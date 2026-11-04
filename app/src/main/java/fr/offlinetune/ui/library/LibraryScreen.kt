package fr.offlinetune.ui.library

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.library.LibrarySort
import fr.offlinetune.core.library.SortFields
import fr.offlinetune.core.library.SortKey
import fr.offlinetune.library.LibState
import fr.offlinetune.library.LibTrack
import fr.offlinetune.library.TrackFiles
import fr.offlinetune.playback.PlayableTrack
import fr.offlinetune.settings.TabPref
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.player.AddToPlaylistSheet
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch

fun LibTrack.playable() = PlayableTrack(uri, title, artist, album, coverUri)

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private enum class LibTab(val label: String) {
    TITRES("Titres"), ALBUMS("Albums"), ARTISTES("Artistes"), PLAYLISTS("Playlists"), PLUS_ECOUTES("Plus écoutés"), RECENTS("Récents")
}

private sealed interface Detail {
    data class Album(val name: String, val artist: String) : Detail
    data class Artist(val name: String) : Detail
    data class Playlist(val uri: String, val name: String) : Detail
}

private fun LibTrack.fields(plays: Int) = SortFields(
    title = title, artist = artist, track = trackNumber,
    fileName = pathSegments.lastOrNull() ?: title,
    location = pathSegments.dropLast(1).joinToString("/"),
    added = lastModified, plays = plays,
)

/** A group (album / artist) shown like a track: [sample] gives its cover, [tracks] its content. */
private data class Group(val name: String, val sub: String, val sample: LibTrack, val tracks: List<LibTrack>)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val tracks by app.library.tracks.collectAsState()
    val state by app.library.state.collectAsState()
    val settings by app.settings.flow.collectAsState(initial = app.latest)
    val statsVersion by app.library.statsVersion.collectAsState()
    val scope = rememberCoroutineScope()
    val files = remember { TrackFiles(context.applicationContext, app.library) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var info by remember { mutableStateOf<LibTrack?>(null) }
    val selection = remember { mutableStateListOf<String>() }
    var addTo by remember { mutableStateOf<List<LibTrack>?>(null) }
    var toDelete by remember { mutableStateOf<List<LibTrack>?>(null) }
    var missing by remember { mutableStateOf<List<LibTrack>?>(null) }

    // Scan once per app start; afterwards only with « Actualiser » (not on every tab switch).
    LaunchedEffect(Unit) { app.library.loadCache(); app.library.refreshOnce() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scope.launch { app.settings.update { it.copy(libraryTreeUri = uri.toString()) }; app.library.refresh() }
        }
    }

    info?.let { t -> TrackInfoScreen(app, t) { info = null }; return }

    BackHandler(enabled = selection.isNotEmpty()) { selection.clear() }
    BackHandler(enabled = selection.isEmpty() && detail != null) { detail = null }

    val libTab = LibTab.entries[tab]
    val pref = settings.tabPrefs[libTab.name] ?: TabPref()
    fun setPref(p: TabPref) = scope.launch { app.settings.update { it.copy(tabPrefs = it.tabPrefs + (libTab.name to p)) } }
    val plays: (LibTrack) -> Int = { app.library.stats.count(it.uri) }
    val byUri = remember(tracks) { tracks.associateBy { it.uri } }

    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) tracks else tracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || (it.album?.lowercase()?.contains(q) == true)
        }
    }
    val sortedTracks = remember(filtered, pref, statsVersion) { LibrarySort.sort(filtered, pref.sort, pref.desc) { it.fields(plays(it)) } }

    fun groupFields(g: Group) = SortFields(g.name, g.sample.albumArtist ?: g.sample.artist, null, g.name,
        g.sample.pathSegments.dropLast(1).joinToString("/"), g.tracks.maxOf { it.lastModified }, g.tracks.sumOf { plays(it) })

    fun selected() = LibrarySort.inSelectionOrder(selection.toList(), tracks) { it.uri }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (selection.isNotEmpty()) {
            SelectionBar(
                count = selection.size,
                onPlay = { app.player.playList(selected().map { it.playable() }, 0); selection.clear() },
                onShuffle = { app.player.playList(selected().shuffled().map { it.playable() }, 0); selection.clear() },
                onNext = { app.player.playNext(selected().map { it.playable() }); selection.clear() },
                onEnd = { app.player.addToEnd(selected().map { it.playable() }); selection.clear() },
                onPlaylist = { addTo = selected() },
                onShare = { files.share(selected()) },
                onDelete = { toDelete = selected() },
                onClose = { selection.clear() },
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (detail != null) IconButton(onClick = { detail = null }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
                Text(
                    when (val d = detail) { is Detail.Album -> d.name; is Detail.Artist -> d.name; is Detail.Playlist -> d.name; null -> "Bibliothèque" },
                    style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    scope.launch {
                        app.library.refresh()
                        missing = app.library.missingCover().takeIf { it.isNotEmpty() }
                    }
                }) { Icon(Icons.Rounded.Refresh, "Actualiser") }
            }
        }
        if (state == LibState.Scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))

        val onTrack: (List<LibTrack>, Int) -> Unit = { list, i ->
            if (selection.isNotEmpty()) {
                val u = list[i].uri
                if (u in selection) selection.remove(u) else selection.add(u)
            } else {
                // Queue = the touched track and the ones after it, in the shown order.
                app.player.playList(LibrarySort.queueFrom(list, i).map { it.playable() }, 0)
            }
        }
        val onLong: (LibTrack) -> Unit = { t -> if (t.uri !in selection) selection.add(t.uri) }
        val actions = TrackActions(
            addToPlaylist = { addTo = listOf(it) },
            playNext = { app.player.playNext(listOf(it.playable())); Toast.makeText(context, "Lu après le morceau en cours", Toast.LENGTH_SHORT).show() },
            addToEnd = { app.player.addToEnd(listOf(it.playable())); Toast.makeText(context, "Ajouté à la fin de la file", Toast.LENGTH_SHORT).show() },
            delete = { toDelete = listOf(it) },
            share = { files.share(listOf(it)) },
            info = { info = it },
        )

        when {
            state == LibState.NeedsFolder || state is LibState.Error -> EmptyLibrary(
                (state as? LibState.Error)?.message ?: "Choisissez le dossier où se trouve votre musique sur le téléphone.",
            ) { pickFolder.launch(null) }
            detail is Detail.Playlist -> PlaylistDetail(app, (detail as Detail.Playlist).uri, onGone = { detail = null }, onRenamed = { f -> detail = Detail.Playlist(f.uri, f.name) })
            detail != null -> {
                val list = when (val d = detail!!) {
                    is Detail.Album -> tracks.filter { (it.album ?: "") == d.name && (it.albumArtist ?: it.artist) == d.artist }
                        .sortedWith(compareBy<LibTrack> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title })
                    is Detail.Artist -> LibrarySort.sort(tracks.filter { it.artist == d.name || it.albumArtist == d.name }, pref.sort, pref.desc) { it.fields(plays(it)) }
                    is Detail.Playlist -> emptyList()
                }
                TrackList(app, list, grid = false, selection, actions, onTrack, onLong)
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
                ScrollableTabRow(selectedTabIndex = tab, containerColor = Color.Transparent, edgePadding = 12.dp) {
                    LibTab.entries.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label) }) }
                }
                if (libTab == LibTab.TITRES || libTab == LibTab.ALBUMS || libTab == LibTab.ARTISTES) {
                    SortBar(pref, onChange = { setPref(it) })
                }
                if (tracks.isEmpty() && state == LibState.Idle && libTab != LibTab.PLAYLISTS) {
                    EmptyHint("Aucun morceau trouvé dans ce dossier. Téléchargez un morceau ou choisissez un autre dossier dans Réglages.")
                } else when (libTab) {
                    LibTab.TITRES -> TrackList(app, sortedTracks, pref.grid, selection, actions, onTrack, onLong)
                    LibTab.ALBUMS -> {
                        val groups = remember(filtered, pref, statsVersion) {
                            val g = filtered.groupBy { (it.album ?: "Sans album") to (it.albumArtist ?: it.artist) }
                                .map { (k, v) -> Group(k.first, "${k.second} · ${v.size} titre${if (v.size > 1) "s" else ""}", v.first(), v) }
                            LibrarySort.sort(g, pref.sort, pref.desc, ::groupFields)
                        }
                        GroupList(groups, pref.grid) { gr -> detail = Detail.Album(gr.name, gr.sample.albumArtist ?: gr.sample.artist) }
                    }
                    LibTab.ARTISTES -> {
                        val groups = remember(filtered, pref, statsVersion) {
                            val g = filtered.groupBy { it.artist }.map { (k, v) -> Group(k, "${v.size} titre${if (v.size > 1) "s" else ""}", v.first(), v) }
                            LibrarySort.sort(g, pref.sort, pref.desc, ::groupFields)
                        }
                        GroupList(groups, pref.grid) { gr -> detail = Detail.Artist(gr.name) }
                    }
                    LibTab.PLAYLISTS -> PlaylistsTab(app) { p -> detail = Detail.Playlist(p.file.uri, p.name) }
                    LibTab.PLUS_ECOUTES -> {
                        val list = remember(tracks, statsVersion) { app.library.stats.mostPlayed(200).mapNotNull { byUri[it] } }
                        if (list.isEmpty()) EmptyHint("Les morceaux que vous écoutez le plus apparaîtront ici.")
                        else TrackList(app, list, grid = false, selection, actions, onTrack, onLong, showPlays = true)
                    }
                    LibTab.RECENTS -> {
                        val list = remember(tracks, statsVersion) { app.library.stats.recent(200).mapNotNull { byUri[it] } }
                        if (list.isEmpty()) EmptyHint("Les morceaux écoutés récemment apparaîtront ici.")
                        else TrackList(app, list, grid = false, selection, actions, onTrack, onLong)
                    }
                }
            }
        }
    }

    addTo?.let { list ->
        AddToPlaylistSheet(app, list) { msg -> addTo = null; selection.clear(); msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }
    }
    toDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (list.size == 1) "Supprimer « ${list[0].title} » ?" else "Supprimer ${list.size} morceaux ?") },
            text = {
                Text(
                    "Suppression définitive du téléphone : le fichier audio et ses paroles, ou tout son dossier " +
                        "(avec la pochette) quand le morceau est seul dans son dossier. Cette action est irréversible.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null; selection.clear()
                    scope.launch {
                        val n = runCatching { files.delete(list) }.getOrDefault(0)
                        Toast.makeText(context, "$n morceau(x) supprimé(s)", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Supprimer définitivement", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
    missing?.let { list ->
        AlertDialog(
            onDismissRequest = { missing = null },
            title = { Text("${list.size} morceau${if (list.size > 1) "x" else ""} sans pochette") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    itemsIndexed(list) { _, t ->
                        Column(Modifier.fillMaxWidth().clickable { missing = null; info = t }.padding(vertical = 6.dp)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { missing = null }) { Text("OK") } },
        )
    }
}

class TrackActions(
    val addToPlaylist: (LibTrack) -> Unit,
    val playNext: (LibTrack) -> Unit,
    val addToEnd: (LibTrack) -> Unit,
    val delete: (LibTrack) -> Unit,
    val share: (LibTrack) -> Unit,
    val info: (LibTrack) -> Unit,
)

@Composable
private fun SortBar(pref: TabPref, onChange: (TabPref) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { open = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null); Spacer(Modifier.width(6.dp)); Text(pref.sort.label)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SortKey.entries.forEach { k ->
                    DropdownMenuItem(
                        text = { Text(k.label) },
                        trailingIcon = { if (k == pref.sort) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = { open = false; onChange(pref.copy(sort = k, desc = k == SortKey.ADDED || k == SortKey.PLAYS)) },
                    )
                }
            }
        }
        TextButton(onClick = { onChange(pref.copy(desc = !pref.desc)) }) { Text(if (pref.desc) "Décroissant" else "Croissant") }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onChange(pref.copy(grid = !pref.grid)) }) {
            Icon(if (pref.grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (pref.grid) "Afficher en liste" else "Afficher en grille")
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, onPlay: () -> Unit, onShuffle: () -> Unit, onNext: () -> Unit, onEnd: () -> Unit,
    onPlaylist: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), RoundedCornerShape(22.dp), strong = true) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Annuler la sélection") }
                Text("$count sélectionné${if (count > 1) "s" else ""}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SelAction(Icons.Rounded.PlayArrow, "Lire", onPlay)
                SelAction(Icons.Rounded.Shuffle, "Aléatoire", onShuffle)
                SelAction(Icons.Rounded.SkipNext, "Lire après", onNext)
                SelAction(Icons.AutoMirrored.Rounded.QueueMusic, "À la fin", onEnd)
                SelAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", onPlaylist)
                SelAction(Icons.Rounded.Share, "Partager", onShare)
                SelAction(Icons.Rounded.Delete, "Supprimer", onDelete)
            }
        }
    }
}

@Composable
private fun SelAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Muted, maxLines = 1)
    }
}

@Composable
private fun TrackList(
    app: App, list: List<LibTrack>, grid: Boolean, selection: List<String>, actions: TrackActions,
    onTrack: (List<LibTrack>, Int) -> Unit, onLong: (LibTrack) -> Unit, showPlays: Boolean = false,
) {
    val current by app.player.state.collectAsState()
    if (grid) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(list, key = { i, t -> "$i:${t.uri}" }) { i, t ->
                TrackCard(t, current.mediaId == t.uri, t.uri in selection, onClick = { onTrack(list, i) }, onLong = { onLong(t) })
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (list.size > 1 && selection.isEmpty()) item {
            Row(
                Modifier.fillMaxWidth().clickable { app.player.playList(list.shuffled().map { it.playable() }, 0) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Shuffle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Lecture aléatoire · ${list.size} titres", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
        }
        itemsIndexed(list, key = { i, t -> "$i:${t.uri}" }) { i, t ->
            TrackRow(
                t, playing = current.mediaId == t.uri, selected = t.uri in selection, actions = actions,
                extra = if (showPlays) "${app.library.stats.count(t.uri)}×" else null,
                onLongClick = { onLong(t) },
            ) { onTrack(list, i) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    t: LibTrack, playing: Boolean, selected: Boolean = false, actions: TrackActions? = null, extra: String? = null,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            CoverImage(Covers.key(t.coverUri, t.uri), RoundedCornerShape(8.dp), Modifier.size(48.dp), size = 128)
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomEnd).size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                color = if (playing) MaterialTheme.colorScheme.primary else Ink.Text,
            )
            Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Text(extra ?: formatDuration(t.durationMs), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        if (actions != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Plus d'actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Ajouter à une liste de lecture") { menu = false; actions.addToPlaylist(t) }
                    MenuItem(Icons.Rounded.SkipNext, "Lire après le morceau en cours") { menu = false; actions.playNext(t) }
                    MenuItem(Icons.Rounded.SouthEast, "Ajouter à la fin de la file") { menu = false; actions.addToEnd(t) }
                    MenuItem(Icons.Rounded.Share, "Partager") { menu = false; actions.share(t) }
                    MenuItem(Icons.Rounded.Info, "Infos (modifier)") { menu = false; actions.info(t) }
                    MenuItem(Icons.Rounded.Delete, "Supprimer du téléphone", danger = true) { menu = false; actions.delete(t) }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else Ink.Text) },
        leadingIcon = { Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else Ink.Muted) },
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackCard(t: LibTrack, playing: Boolean, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Box {
            CoverImage(Covers.key(t.coverUri, t.uri), RoundedCornerShape(16.dp), Modifier.fillMaxWidth().aspectRatio(1f), size = 400)
            if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            color = if (playing) MaterialTheme.colorScheme.primary else Ink.Text,
        )
        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
    }
}

@Composable
private fun GroupList(groups: List<Group>, grid: Boolean, onOpen: (Group) -> Unit) {
    if (grid) {
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(groups, key = { i, g -> "$i:${g.name}:${g.sub}" }) { _, g ->
                Column(Modifier.clickable { onOpen(g) }) {
                    CoverImage(Covers.key(g.sample.coverUri, g.sample.uri), RoundedCornerShape(16.dp), Modifier.fillMaxWidth().aspectRatio(1f), size = 400)
                    Spacer(Modifier.height(6.dp))
                    Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(g.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(groups, key = { i, g -> "$i:${g.name}:${g.sub}" }) { _, g ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(g) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(Covers.key(g.sample.coverUri, g.sample.uri), RoundedCornerShape(10.dp), Modifier.size(56.dp), size = 160)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(g.sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
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
}
