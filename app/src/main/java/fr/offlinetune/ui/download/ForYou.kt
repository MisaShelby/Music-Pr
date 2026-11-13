package fr.offlinetune.ui.download

import fr.offlinetune.ui.theme.rounded
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.core.search.AlbumCard
import fr.offlinetune.core.search.Provider
import fr.offlinetune.core.search.ProviderHit
import fr.offlinetune.core.search.RecRow
import fr.offlinetune.core.search.SongResult
import fr.offlinetune.core.search.providers.SpotifyPlaylist
import fr.offlinetune.ui.RemoteImage
import fr.offlinetune.ui.glass.PlatformBadge
import fr.offlinetune.ui.theme.Ink

/** Copies a direct link to the clipboard. */
fun copyLink(context: Context, url: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Lien", url))
    Toast.makeText(context, "Lien copié", Toast.LENGTH_SHORT).show()
}

/** Link to copy for a song: the filtered platform's, else Spotify > Deezer > Apple Music > YouTube > SoundCloud > Tidal. */
fun bestLink(song: SongResult, filter: Provider? = null): String? =
    (filter?.let { song.hits[it] } ?: listOf(Provider.SPOTIFY, Provider.DEEZER, Provider.APPLE_MUSIC, Provider.YOUTUBE_MUSIC, Provider.SOUNDCLOUD, Provider.TIDAL)
        .firstNotNullOfOrNull { song.hits[it] })?.url

fun hitAsSong(h: ProviderHit) = SongResult(h.title, h.artist, h.album, h.durationSec, h.coverUrl, mapOf(h.provider to h))

@Composable
fun CopyLinkButton(url: String?) {
    val context = LocalContext.current
    if (url != null) IconButton(onClick = { copyLink(context, url) }) { Icon(Icons.Rounded.ContentCopy, "Copier le lien", tint = Ink.Muted) }
}

/** « Pour vous »: rows of covers built from the user's library, plus their Spotify playlists when connected. */
@Composable
fun ForYouHome(state: ForYouState, onSong: (SongResult) -> Unit, onAlbum: (AlbumCard) -> Unit, onPlaylist: (SpotifyPlaylist) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Pour vous", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp))
        when (state) {
            ForYouState.Loading -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
                Text("Suggestions d'après votre bibliothèque…", color = Ink.Muted)
            }
            is ForYouState.Ready -> {
                state.spotify?.takeIf { it.isNotEmpty() }?.let { lists ->
                    RowTitle("Vos playlists Spotify")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(lists, key = { i, p -> "$i:${p.id}" }) { _, p -> Card(p.coverUrl, p.name, "${p.trackCount} titres", Provider.SPOTIFY) { onPlaylist(p) } }
                    }
                }
                state.spotifyError?.let { Text("Spotify : $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
                if (state.rows.isEmpty() && state.spotify.isNullOrEmpty()) {
                    Text("Ajoutez de la musique à votre bibliothèque pour recevoir des suggestions.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
                }
                state.rows.forEach { row ->
                    RowTitle(row.title)
                    when (row) {
                        is RecRow.Tracks -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(row.hits, key = { i, h -> "$i:${h.url}" }) { _, h -> Card(h.coverUrl, h.title, h.artist, null) { onSong(hitAsSong(h)) } }
                        }
                        is RecRow.Albums -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(row.albums, key = { i, a -> "$i:${a.id}" }) { _, a -> Card(a.coverUrl, a.title, a.year?.toString() ?: a.artist, null) { onAlbum(a) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))

@Composable
private fun Card(cover: String?, title: String, subtitle: String, badge: Provider?, onClick: () -> Unit) {
    Column(Modifier.width(138.dp).clickable(onClick = onClick)) {
        RemoteImage(cover, rounded(16.dp), Modifier.size(138.dp))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            badge?.let { PlatformBadge(it, 16.dp); Spacer(Modifier.width(6.dp)) }
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        }
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
    }
}

/** Album tracks or Spotify playlist tracks: displayed only; touching one opens its sources. */
@Composable
fun SongListScreen(padding: PaddingValues, title: String, subtitle: String?, songs: List<SongResult>, onBack: () -> Unit, onSong: (SongResult) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted) }
                }
            }
            if (songs.isEmpty()) Text("Aucun titre.", color = Ink.Muted, modifier = Modifier.padding(20.dp))
        }
        itemsIndexed(songs, key = { i, s -> "$i:${s.hits.values.first().url}" }) { _, s -> SongRow(s, null) { onSong(s) } }
    }
}
