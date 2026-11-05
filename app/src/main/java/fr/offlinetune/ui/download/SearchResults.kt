package fr.offlinetune.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.search.FederatedSearch
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderStatus
import fr.offlinetune.core.search.SearchOutcome
import fr.offlinetune.core.search.SongResult
import fr.offlinetune.ui.RemoteImage
import fr.offlinetune.ui.glass.GlassChip
import fr.offlinetune.ui.glass.PlatformBadge
import fr.offlinetune.ui.theme.Ink

fun chipLabel(p: Provider, status: ProviderStatus?): String = when (status) {
    ProviderStatus.NeedsKey -> "${p.label} · clé requise"
    ProviderStatus.BadKey -> "${p.label} · clé refusée"
    is ProviderStatus.Failed -> "${p.label} · indisponible"
    else -> p.label
}

/** Filter chips + songs; each song shows the badges of the platforms where it was found. */
@Composable
fun SearchResults(outcome: SearchOutcome, filter: Provider?, onFilter: (Provider?) -> Unit, onOpen: (SongResult) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("Tout", selected = filter == null) { onFilter(null) }
            Provider.entries.forEach { p ->
                val st = outcome.status[p]
                // « Sources perso » chip only when the user has some.
                if (p == Provider.CUSTOM && st == ProviderStatus.Ok && outcome.songs.none { Provider.CUSTOM in it.hits }) return@forEach
                GlassChip(chipLabel(p, st), selected = filter == p, enabled = st == ProviderStatus.Ok) { onFilter(p) }
            }
        }
        // Why a platform is missing (e.g. Spotify's own refusal message).
        outcome.status.forEach { (p, st) ->
            if (st is ProviderStatus.Failed) Text(
                "${p.label} : ${st.message}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        val songs = FederatedSearch.filter(outcome, filter)
        if (songs.isEmpty()) {
            Text("Aucun résultat sur cette plateforme.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
        }
        songs.forEach { song -> SongRow(song, filter) { onOpen(song) } }
        Text(
            "Les pastilles indiquent les plateformes où la chanson existe.",
            style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
    }
}

@Composable
fun SongRow(song: SongResult, filter: Provider?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(song.coverUrl, RoundedCornerShape(10.dp), Modifier.size(54.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            val sub = listOfNotNull(song.artist, song.album, song.durationSec?.let { "%d:%02d".format(it / 60, it % 60) }).joinToString(" · ")
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Provider.entries.filter { it in song.hits }.forEach { PlatformBadge(it, 18.dp) }
            }
        }
        CopyLinkButton(bestLink(song, filter))
    }
}
