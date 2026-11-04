package fr.offlinetune.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.offlinetune.App
import fr.offlinetune.playback.PlayerUiState
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.glass.AppBackdrop
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.library.formatDuration
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "Opus · 143 kb/s" from the file itself (container + measured bitrate). */
fun qualityLabel(context: Context, uri: String): String? {
    val codec = when (uri.substringAfterLast('.').substringBefore('?').lowercase()) {
        "opus", "ogg" -> "Opus"; "m4a", "aac" -> "AAC"; "mp3" -> "MP3"; "flac" -> "FLAC"; "wav" -> "WAV"; else -> null
    }
    val kbps = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, Uri.parse(uri)); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000) } finally { r.release() }
    }.getOrNull()
    return when {
        codec != null && kbps != null && kbps > 0 -> "$codec · $kbps kb/s"
        codec != null -> codec
        else -> null
    }
}

/** Now playing, direction A: blurred cover colours over the whole screen, big cover, glass bar at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(app: App, state: PlayerUiState, coverKey: String?, lrcUri: String?, onClose: () -> Unit, onOpenSound: () -> Unit) {
    val context = LocalContext.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var addToList by remember { mutableStateOf(false) }
    var lrc by remember(lrcUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(lrcUri) { lrc = lrcUri?.let { app.library.readText(it) } }
    val quality by produceState<String?>(null, state.mediaId) {
        value = state.mediaId?.let { id -> withContext(Dispatchers.IO) { qualityLabel(context, id) } }
    }
    val tracks by app.library.tracks.collectAsState()
    val current = tracks.firstOrNull { it.uri == state.mediaId }

    AppBackdrop(coverKey, MaterialTheme.colorScheme.primary, Modifier.clickable(enabled = false) {}) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Réduire") }
                Text("En lecture", style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsView(lrc, state.positionMs, onSeek = { app.player.seekTo(it) })
                } else {
                    CoverImage(
                        coverKey, RoundedCornerShape(22.dp),
                        Modifier.fillMaxWidth().aspectRatio(1f).shadow(28.dp, RoundedCornerShape(22.dp)),
                        size = 1024,
                    )
                }
            }

            Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" — "),
                style = MaterialTheme.typography.bodyLarge, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            var dragging by remember { mutableStateOf<Float?>(null) }
            val dur = state.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (state.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { app.player.seekTo((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatDuration(dragging?.let { (it * dur).toLong() } ?: state.positionMs).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    quality?.let { q ->
                        GlassSurface(shape = RoundedCornerShape(50)) {
                            Text(q, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                        }
                    }
                }
                Text("-" + formatDuration((state.durationMs - state.positionMs).coerceAtLeast(0)).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Aléatoire", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else Ink.Muted)
                }
                IconButton(onClick = { app.player.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Précédent", Modifier.size(38.dp)) }
                Box(
                    Modifier.size(70.dp).shadow(16.dp, CircleShape).background(Color.White, CircleShape).clickable { app.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pause" else "Lecture", tint = Ink.Background, modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { app.player.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Suivant", Modifier.size(38.dp)) }
                IconButton(onClick = { app.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Répéter le morceau"; Player.REPEAT_MODE_ALL -> "Répéter tout"; else -> "Pas de répétition" },
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Ink.Muted else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            GlassSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp), RoundedCornerShape(24.dp), strong = true) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton(Icons.Rounded.Lyrics, "Paroles", active = showLyrics) { showLyrics = !showLyrics }
                    BarButton(Icons.AutoMirrored.Rounded.QueueMusic, "File") { showQueue = true }
                    BarButton(Icons.Rounded.Equalizer, "Son", onClick = onOpenSound)
                    BarButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", enabled = current != null) { addToList = true }
                }
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = Ink.Surface) {
            Text("File d'attente", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
            LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
                itemsIndexed(state.queue) { i, q ->
                    Row(
                        Modifier.fillMaxWidth().clickable { app.player.skipTo(i) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == state.index) MaterialTheme.colorScheme.primary else Ink.Text)
                            Text(q.artist, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                        }
                        if (i != state.index) IconButton(onClick = { app.player.removeAt(i) }) { Icon(Icons.Rounded.Close, "Retirer de la file") }
                        else Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
    if (addToList && current != null) {
        AddToPlaylistSheet(app, listOf(current)) { msg ->
            addToList = false
            msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when { !enabled -> Ink.Muted.copy(alpha = 0.5f); active -> MaterialTheme.colorScheme.primary; else -> Ink.Text }
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}
