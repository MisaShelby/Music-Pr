package fr.offlinetune.ui.player

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.playback.PlayerUiState
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.glass.Glass
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.theme.Ink

@Composable
fun MiniPlayer(state: PlayerUiState, coverKey: String?, onOpen: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
    val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp), RoundedCornerShape(22.dp), strong = true) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(coverKey, RoundedCornerShape(10.dp), Modifier.size(44.dp), size = 128)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(state.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            }
            IconButton(onClick = onToggle) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Lecture")
            }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Suivant") }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Glass.Fill)) {
            Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
    }
}
