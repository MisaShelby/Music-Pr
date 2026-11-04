package fr.offlinetune.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.model.SearchHit
import fr.offlinetune.core.sheet.InputResult
import fr.offlinetune.ui.RemoteImage
import fr.offlinetune.ui.theme.Ink

/** Two tabs: "Titres" (Deezer catalogue, clean info) and "YouTube" (remixes, live, tracks missing from Deezer). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsList(results: InputResult.Results, onOpen: (SearchHit) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(if (results.deezer.isEmpty()) 1 else 0) }
    Column {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Titres (${results.deezer.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("YouTube (${results.youtube.size})") })
        }
        val list = if (tab == 0) results.deezer else results.youtube
        if (list.isEmpty()) Text("Aucun résultat dans cet onglet.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
        list.forEach { hit ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(hit) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteImage(hit.thumbUrl, RoundedCornerShape(8.dp), Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(hit.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(hit.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
                }
                hit.durationSec?.let { Text("%d:%02d".format(it / 60, it % 60), style = MaterialTheme.typography.labelMedium, color = Ink.Muted) }
            }
        }
    }
}
