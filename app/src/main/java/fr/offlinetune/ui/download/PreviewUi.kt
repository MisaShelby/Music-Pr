package fr.offlinetune.ui.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.search.SongResult
import fr.offlinetune.playback.PreviewState
import fr.offlinetune.ui.RemoteImage
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.theme.Ink

fun songKey(song: SongResult) = "song:${song.artist}|${song.title}|${song.durationSec}"
fun sourceKey(url: String) = "src:$url"

private fun time(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun previewState(): PreviewState {
    val app = LocalContext.current.applicationContext as App
    val st by app.preview.state.collectAsState()
    return st
}

/** Listen to a search result without downloading it. */
@Composable
fun ListenSongButton(song: SongResult, size: Dp = 40.dp) {
    val app = LocalContext.current.applicationContext as App
    ListenButton(songKey(song), size) {
        app.preview.toggle(songKey(song), song.title, song.artist, song.coverUrl) { app.downloadModel.listenStream(song) }
    }
}

/** Listen to one exact source before choosing it. */
@Composable
fun ListenSourceButton(url: String, title: String, artist: String, coverUrl: String?, size: Dp = 40.dp) {
    val app = LocalContext.current.applicationContext as App
    ListenButton(sourceKey(url), size) {
        app.preview.toggle(sourceKey(url), title, artist, coverUrl) { app.downloadModel.sourceStream(url) }
    }
}

@Composable
private fun ListenButton(key: String, size: Dp, onClick: () -> Unit) {
    val st = previewState()
    val mine = st.key == key
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (mine && st.error == null) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f))
            .clickable(onClickLabel = "Écouter", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            mine && st.loading && !st.playing -> CircularProgressIndicator(Modifier.size(size * 0.5f), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            mine && st.playing -> Icon(Icons.Rounded.Pause, "Pause", Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onPrimary)
            else -> Icon(Icons.Rounded.PlayArrow, "Écouter", Modifier.size(size * 0.6f), tint = if (mine && st.error == null) MaterialTheme.colorScheme.onPrimary else Color.White)
        }
    }
}

/** Floating bar while something found online is being listened to. */
@Composable
fun PreviewBar(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as App
    val st = previewState()
    if (st.key == null) return
    GlassSurface(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(st.coverUrl, RoundedCornerShape(10.dp), Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Headphones, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Écoute sans téléchargement", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(st.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        st.error ?: if (st.loading && !st.playing) "Chargement…" else "${st.artist} · ${time(st.positionMs)} / ${time(st.durationMs)}",
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (st.error != null) MaterialTheme.colorScheme.error else Ink.Muted,
                    )
                }
                if (st.error == null) IconButton(onClick = { if (st.playing) app.preview.pause() else app.preview.resume() }) {
                    if (st.loading && !st.playing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (st.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (st.playing) "Pause" else "Lecture")
                }
                IconButton(onClick = { app.preview.stop() }) { Icon(Icons.Rounded.Close, "Arrêter l'écoute") }
            }
            val progress = if (st.durationMs > 0) (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = Color.White.copy(alpha = 0.12f),
            )
        }
    }
}

/**
 * « Aperçu » on the sheet: the source that will really be downloaded, to check it is the right song first.
 * Starts a third of the way in (past the intro); the bar lets the user move anywhere.
 */
@Composable
fun SourcePreviewCard(url: String, title: String, artist: String, coverUrl: String?) {
    val app = LocalContext.current.applicationContext as App
    val st = previewState()
    val key = sourceKey(url)
    val mine = st.key == key
    var dragging by remember { mutableStateOf<Float?>(null) }
    GlassSurface(Modifier.fillMaxWidth().padding(vertical = 8.dp), RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ListenButton(key, 52.dp) { app.preview.toggle(key, title, artist, coverUrl, startFraction = 0.3f) { app.downloadModel.sourceStream(url) } }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aperçu avant téléchargement", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            !mine -> "Écoutez la source pour vérifier que c'est bien le bon morceau."
                            st.error != null -> st.error
                            st.loading && !st.playing -> "Chargement de l'aperçu…"
                            else -> "${time(dragging?.let { (it * st.durationMs).toLong() } ?: st.positionMs)} / ${time(st.durationMs)}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mine && st.error != null) MaterialTheme.colorScheme.error else Ink.Muted,
                    )
                }
            }
            if (mine && st.durationMs > 0 && st.error == null) {
                Slider(
                    value = dragging ?: (st.positionMs.toFloat() / st.durationMs).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = { dragging?.let { app.preview.seekTo((it * st.durationMs).toLong()) }; dragging = null },
                    colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.15f)),
                )
            }
        }
    }
}
