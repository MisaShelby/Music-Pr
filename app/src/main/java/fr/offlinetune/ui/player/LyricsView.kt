package fr.offlinetune.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.offlinetune.core.lyrics.LrcFormat
import androidx.compose.material.icons.rounded.EditNote
import fr.offlinetune.ui.theme.Ink

/** Synced lyrics scroll and highlight the current line (tap a line to seek); plain lyrics are shown as text. */
@Composable
fun LyricsView(lrc: String?, positionMs: Long, onSeek: (Long) -> Unit, onWrite: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (lrc.isNullOrBlank()) {
        androidx.compose.foundation.layout.Column(modifier.padding(24.dp)) {
            Text("Pas de paroles pour ce morceau.", color = Ink.Muted)
            if (onWrite != null) androidx.compose.material3.Button(onClick = onWrite, modifier = Modifier.padding(top = 12.dp)) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.EditNote, null)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 6.dp))
                Text("Écrire et synchroniser les paroles")
            }
        }
        return
    }
    val lines = remember(lrc) { LrcFormat.parse(lrc) }
    if (lines.isEmpty()) {
        val plain = remember(lrc) { lrc.lines().filterNot { it.matches(Regex("""^\[[a-z]+:.*]$""")) }.joinToString("\n").trim() }
        Text(
            plain, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
            modifier = modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        )
        return
    }
    val current = lines.indexOfLast { it.first <= positionMs + 150 }.coerceAtLeast(0)
    val listState = rememberLazyListState()
    LaunchedEffect(current) { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 120.dp, horizontal = 24.dp)) {
        itemsIndexed(lines) { i, (time, text) ->
            val active = i == current
            val color by animateColorAsState(
                when { active -> MaterialTheme.colorScheme.primary; i < current -> Ink.Muted.copy(alpha = 0.6f); else -> Ink.Text.copy(alpha = 0.75f) },
                label = "lyric",
            )
            Text(
                text.ifBlank { "♪" },
                color = color,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = if (active) FontWeight.Black else FontWeight.Bold),
                modifier = Modifier.fillMaxWidth().clickable { onSeek(time) }.padding(vertical = 8.dp),
            )
        }
    }
}
