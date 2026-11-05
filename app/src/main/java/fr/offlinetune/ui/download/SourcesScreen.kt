package fr.offlinetune.ui.download

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.search.DownloadOption
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.SongResult
import fr.offlinetune.core.search.SongSources
import fr.offlinetune.ui.RemoteImage
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.glass.PlatformBadge
import fr.offlinetune.ui.theme.Ink

/** After touching a song: downloadable sources with their real quality, then the platforms to listen on. */
@Composable
fun SourcesScreen(padding: PaddingValues, song: SongResult, sources: SongSources?, onBack: () -> Unit, onChoose: (DownloadOption) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            Text("Sources", style = MaterialTheme.typography.headlineSmall)
        }
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(song.coverUrl, RoundedCornerShape(14.dp), Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(song.artist, song.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            }
        }
        if (sources == null) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(14.dp))
                Text("Recherche des sources et de leur qualité…", color = Ink.Muted)
            }
            return@Column
        }

        Text("Téléchargeable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))
        sources.downloadable.forEach { o ->
            SourceRow(
                badge = { PlatformBadge(o.provider, 34.dp) },
                title = if (o.isMatch) "Correspondance YouTube" else o.label,
                subtitle = "${o.format.label()} · paroles ${if (o.hasLyrics) "✓" else "✗"}",
                action = "Choisir", strongAction = true, link = o.url,
            ) { onChoose(o) }
        }
        sources.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }

        if (sources.listenOnly.isNotEmpty()) {
            Text(
                "Disponible aussi (écoute dans leur appli)", style = MaterialTheme.typography.titleMedium,
                color = Ink.Muted, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            sources.listenOnly.forEach { h: ProviderHit ->
                SourceRow(
                    badge = { PlatformBadge(h.provider, 34.dp) },
                    title = h.provider.label, subtitle = h.quality, action = "Ouvrir ↗", strongAction = false, link = h.url,
                ) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            Text(
                "Ces plateformes protègent leur audio : la chanson s'écoute dans leur appli, elle n'est pas téléchargée.",
                style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SourceRow(badge: @Composable () -> Unit, title: String, subtitle: String, action: String, strongAction: Boolean, link: String? = null, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth().padding(vertical = 5.dp), RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            badge()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            }
            CopyLinkButton(link)
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (strongAction) Color.White else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(action, color = if (strongAction) Ink.Background else Ink.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

