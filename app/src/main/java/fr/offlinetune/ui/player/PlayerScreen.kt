package fr.offlinetune.ui.player

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.offlinetune.App
import fr.offlinetune.playback.PlayerUiState
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.library.formatDuration
import fr.offlinetune.ui.theme.Ink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(app: App, state: PlayerUiState, coverKey: String?, lrcUri: String?, onClose: () -> Unit) {
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var lrc by remember(lrcUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(lrcUri) { lrc = lrcUri?.let { app.library.readText(it) } }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        Modifier.fillMaxSize()
            .background(Ink.Background)
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Ink.Background), endY = 1400f))
            .clickable(enabled = false) {},
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Réduire") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(Icons.Rounded.Lyrics, "Paroles", tint = if (showLyrics) accent else Ink.Text)
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "File d'attente") }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (showLyrics) {
                    LyricsView(lrc, state.positionMs, onSeek = { app.player.seekTo(it) })
                } else {
                    CoverImage(coverKey, RoundedCornerShape(20.dp), Modifier.fillMaxWidth().aspectRatio(1f), size = 1024)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(state.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" — "),
                style = MaterialTheme.typography.bodyLarge, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))

            var dragging by remember { mutableStateOf<Float?>(null) }
            val dur = state.durationMs.coerceAtLeast(1)
            Slider(
                value = dragging ?: (state.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { app.player.seekTo((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(inactiveTrackColor = Ink.SurfaceHigh),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatDuration(dragging?.let { (it * dur).toLong() } ?: state.positionMs).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                Spacer(Modifier.weight(1f))
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { app.player.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Aléatoire", tint = if (state.shuffle) accent else Ink.Muted)
                }
                IconButton(onClick = { app.player.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Précédent", Modifier.size(36.dp))
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(accent).clickable { app.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pause" else "Lecture",
                        tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { app.player.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Suivant", Modifier.size(36.dp))
                }
                IconButton(onClick = { app.player.cycleRepeat() }) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Répéter le morceau"; Player.REPEAT_MODE_ALL -> "Répéter tout"; else -> "Pas de répétition" },
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) Ink.Muted else accent,
                    )
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
                            Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == state.index) accent else Ink.Text)
                            Text(q.artist, maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                        }
                        if (i != state.index) {
                            IconButton(onClick = { app.player.removeAt(i) }) { Icon(Icons.Rounded.Close, "Retirer de la file") }
                        } else Spacer(Modifier.width(48.dp))
                    }
                }
            }
        }
    }
}
