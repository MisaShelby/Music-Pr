package fr.offlinetune.ui.lyrics

import fr.offlinetune.ui.theme.rounded
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.model.LyricsResult
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.library.LibTrack
import fr.offlinetune.library.TrackFiles
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.glass.GlassChip
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.library.playable
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One line being written: its time (null = not placed yet) and its words. */
private class Line(val id: Long, timeMs: Long?, text: String) {
    var timeMs by mutableStateOf(timeMs)
    var text by mutableStateOf(text)
}

/** [mm:ss.xx] as written in .lrc files. */
fun lrcStamp(ms: Long): String {
    val cs = (ms.coerceAtLeast(0) / 10)
    return "%02d:%02d.%02d".format(cs / 6000, (cs / 100) % 60, cs % 100)
}

/**
 * « Paroles »: choose a song (those without lyrics first), then write its lyrics and place each line on the
 * music — the app adds the [mm:ss.xx] times, the user writes the words. Saved as a .lrc in the song's folder.
 */
@Composable
fun LyricsScreen(padding: PaddingValues) {
    val app = LocalContext.current.applicationContext as App
    val target by app.lyricsTarget.collectAsState()
    val tracks by app.library.tracks.collectAsState()
    val track = target?.let { uri -> tracks.firstOrNull { it.uri == uri } }
    if (track != null) {
        BackHandler { app.lyricsTarget.value = null }
        LyricsEditor(padding, track) { app.lyricsTarget.value = null }
    } else {
        SongPicker(padding, tracks) { app.lyricsTarget.value = it.uri }
    }
}

@Composable
private fun SongPicker(padding: PaddingValues, tracks: List<LibTrack>, onPick: (LibTrack) -> Unit) {
    var withLyrics by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val without = tracks.filter { it.lrcUri == null }
    val with = tracks.filter { it.lrcUri != null }
    val shown = (if (withLyrics) with else without).filter {
        query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true)
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Paroles", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            Text(
                "Choisissez une chanson, écrivez ses paroles et placez chaque ligne au bon moment pendant l'écoute : " +
                    "l'appli ajoute les temps [mm:ss.xx] et crée le fichier .lrc dans le dossier de la chanson.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
            GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), rounded(20.dp), strong = true) {
                TextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Titre ou artiste", color = Ink.Muted) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Ink.Muted) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip("Sans paroles · ${without.size}", selected = !withLyrics) { withLyrics = false }
                GlassChip("Avec paroles · ${with.size}", selected = withLyrics) { withLyrics = true }
            }
        }
        if (shown.isEmpty()) item {
            Text(
                if (!withLyrics && without.isEmpty()) "Toutes vos chansons ont des paroles." else "Aucune chanson.",
                color = Ink.Muted, modifier = Modifier.padding(20.dp),
            )
        }
        items(shown, key = { it.uri }) { t ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(Covers.key(t.coverUri, t.uri), rounded(12.dp), Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.EditNote, "Synchroniser", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LyricsEditor(padding: PaddingValues, t: LibTrack, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as App
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.player.state.collectAsState()
    val lines = remember(t.uri) { mutableStateListOf<Line>() }
    var nextId by remember { mutableLongStateOf(1L) }
    var loaded by remember(t.uri) { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun newLine(time: Long?, text: String) = Line(nextId++, time, text)

    // Existing lyrics are loaded to be corrected; the song starts playing.
    LaunchedEffect(t.uri) {
        val lrc = t.lrcUri?.let { app.library.readText(it) }
        if (lrc != null) {
            val timed = LrcFormat.parse(lrc)
            if (timed.isNotEmpty()) timed.forEach { (ms, text) -> lines += newLine(ms, text) }
            else lrc.lines().filterNot { it.matches(Regex("""^\[[a-z]+:.*]$""")) }.map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { lines += newLine(null, it) }
        }
        loaded = true
        if (app.player.state.value.mediaId != t.uri) app.player.playList(listOf(t.playable()), 0)
    }
    val mine = state.mediaId == t.uri
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(mine) { while (true) { pos = if (mine) app.player.livePositionMs() else 0L; delay(50) } }
    val duration = if (mine) state.durationMs else 0L

    fun sortLines() {
        val sorted = lines.sortedWith(compareBy<Line> { it.timeMs == null }.thenBy { it.timeMs ?: 0L })
        lines.clear(); lines += sorted
    }
    val nextUnplaced = lines.firstOrNull { it.timeMs == null }
    val current = lines.indexOfLast { it.timeMs != null && it.timeMs!! <= pos + 100 }
    val listState = rememberLazyListState()
    LaunchedEffect(current) { if (current > 2) listState.animateScrollToItem(current - 2) }

    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            Column(Modifier.weight(1f)) {
                Text("Synchroniser les paroles", style = MaterialTheme.typography.headlineSmall)
                Text("${t.title} · ${t.artist}", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Player: position, ±5 s, play / pause.
        GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), rounded(20.dp), strong = true) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lrcStamp(pos), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { app.player.seekTo((pos - 5000).coerceAtLeast(0)) }, enabled = mine) { Icon(Icons.Rounded.Replay5, "Reculer de 5 s") }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                            .clickable { if (mine) app.player.togglePlay() else app.player.playList(listOf(t.playable()), 0) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (mine && state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Lecture", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    IconButton(onClick = { app.player.seekTo((pos + 5000).coerceAtMost(duration)) }, enabled = mine) { Icon(Icons.Rounded.Forward5, "Avancer de 5 s") }
                    Spacer(Modifier.weight(1f))
                    Text(lrcStamp(duration).substringBefore('.'), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                }
                if (duration > 0) Slider(
                    value = (pos.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { app.player.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                    modifier = Modifier.height(30.dp),
                )
            }
        }

        // Placing lines.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (nextUnplaced != null) {
                Button(
                    onClick = { nextUnplaced.timeMs = app.player.livePositionMs(); sortLines() },
                    enabled = mine, modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Rounded.TouchApp, null); Spacer(Modifier.width(6.dp))
                    Text("Placer : « ${nextUnplaced.text.take(18)}${if (nextUnplaced.text.length > 18) "…" else ""} »", maxLines = 1)
                }
            } else {
                Button(
                    onClick = { lines += newLine(app.player.livePositionMs(), ""); sortLines() },
                    enabled = mine, modifier = Modifier.weight(1f).height(52.dp),
                ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ligne à ${lrcStamp(pos)}") }
            }
            Box(
                Modifier.height(52.dp).clip(rounded(26.dp)).background(Color.White.copy(alpha = 0.12f))
                    .clickable { writing = true }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.EditNote, null); Spacer(Modifier.width(6.dp)); Text("Texte") } }
        }
        Text(
            if (nextUnplaced != null) "Écoutez et touchez « Placer » au moment où chaque ligne commence."
            else "Touchez « Ligne » quand une phrase commence, puis écrivez ses mots. Ou collez tout le texte avec « Texte ».",
            style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            if (loaded && lines.isEmpty()) item {
                Text("Aucune ligne pour l'instant.", color = Ink.Muted, modifier = Modifier.padding(16.dp))
            }
            items(lines, key = { it.id }) { line ->
                val i = lines.indexOf(line)
                val active = i == current
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(rounded(14.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Time: touch to listen from there.
                    Text(
                        line.timeMs?.let { "[${lrcStamp(it)}]" } ?: "[--:--.--]",
                        fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                        color = if (line.timeMs != null) MaterialTheme.colorScheme.primary else Ink.Muted,
                        modifier = Modifier.clip(rounded(8.dp)).clickable(enabled = line.timeMs != null && mine) { app.player.seekTo(line.timeMs!!) }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                    )
                    TextField(
                        line.text, { line.text = it }, placeholder = { Text("Paroles de cette ligne", color = Ink.Muted) },
                        singleLine = true, modifier = Modifier.weight(1f),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = { line.timeMs = app.player.livePositionMs(); sortLines() }, enabled = mine) {
                        Icon(Icons.Rounded.Timer, "Placer cette ligne maintenant", tint = Ink.Muted)
                    }
                    IconButton(onClick = { lines.remove(line) }) { Icon(Icons.Rounded.Close, "Supprimer la ligne", tint = Ink.Muted) }
                }
            }
        }

        message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp)) }
        val placed = lines.count { it.timeMs != null }
        Button(
            onClick = {
                saving = true; message = null
                scope.launch {
                    message = try {
                        val synced = lines.filter { it.timeMs != null }.sortedBy { it.timeMs }
                            .joinToString("\n") { "[${lrcStamp(it.timeMs!!)}]${it.text.trim()}" }.ifBlank { null }
                        val plain = if (synced == null) lines.joinToString("\n") { it.text.trim() }.ifBlank { null } else null
                        val info = TrackInfo(t.title, t.artist, t.album, durationSec = t.durationMs?.let { (it / 1000).toInt() })
                        TrackFiles(context.applicationContext, app.library).saveLyricsFile(t, LrcFormat.build(info, LyricsResult(synced, plain)))
                        val skipped = if (synced != null) lines.size - placed else 0
                        "Paroles enregistrées (.lrc)" + if (skipped > 0) " — $skipped ligne(s) sans temps ignorée(s)." else "."
                    } catch (e: Throwable) {
                        "Enregistrement impossible : ${e.message ?: "erreur"}"
                    }
                    saving = false
                }
            },
            enabled = !saving && lines.any { it.text.isNotBlank() },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
        ) { Text(if (saving) "Enregistrement…" else "Enregistrer · $placed ligne(s) placée(s)", style = MaterialTheme.typography.titleMedium) }
    }

    if (writing) {
        var text by remember { mutableStateOf(lines.filter { it.timeMs == null }.joinToString("\n") { it.text }) }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Écrire les paroles") },
            text = {
                Column {
                    Text("Une ligne par phrase. Vous placerez ensuite chaque ligne sur la musique.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 360.dp),
                        placeholder = { Text("Collez ou tapez les paroles ici") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lines.removeAll { it.timeMs == null }
                    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { lines += newLine(null, it) }
                    sortLines()
                    writing = false
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("Annuler") } },
        )
    }
}
