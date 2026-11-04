package fr.offlinetune.ui.library

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.lyrics.LrcFormat
import fr.offlinetune.core.lyrics.LrclibClient
import fr.offlinetune.core.model.TrackInfo
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.download.DownloadQueue
import fr.offlinetune.library.LibTrack
import fr.offlinetune.library.TrackFiles
import fr.offlinetune.ui.BytesImage
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** « Info »: details of a saved track, editable (tags written into the file), cover and lyrics included. */
@Composable
fun TrackInfoScreen(app: App, t: LibTrack, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    var title by remember { mutableStateOf(t.title) }
    var artist by remember { mutableStateOf(t.artist) }
    var album by remember { mutableStateOf(t.album ?: "") }
    var albumArtist by remember { mutableStateOf(t.albumArtist ?: "") }
    var year by remember { mutableStateOf(t.year?.toString() ?: "") }
    var track by remember { mutableStateOf(t.trackNumber?.toString() ?: "") }
    var genre by remember { mutableStateOf("") }
    var newCover by remember { mutableStateOf<ByteArray?>(null) }
    var lyrics by remember { mutableStateOf<String?>(null) }
    var lyricsChanged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(t.uri) {
        lyrics = t.lrcUri?.let { app.library.readText(it) } ?: ""
        genre = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(context, Uri.parse(t.uri)); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE) } finally { r.release() }
            }.getOrNull() ?: ""
        }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            newCover = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let(DownloadQueue::toSquareJpeg) }.getOrNull()
            }
        }
    }
    val files = remember { TrackFiles(context.applicationContext, app.library) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            Text("Infos du morceau", style = MaterialTheme.typography.headlineSmall)
        }
        if (busy != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(busy!!, color = Ink.Muted) }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.align(Alignment.CenterHorizontally).clickable { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            if (newCover != null) BytesImage(newCover, RoundedCornerShape(18.dp), Modifier.size(200.dp))
            else CoverImage(Covers.key(t.coverUri, t.uri), RoundedCornerShape(18.dp), Modifier.size(200.dp), size = 600)
        }
        Text(
            if (t.hasArtwork == false && newCover == null) "Pas de pochette — touchez pour en choisir une" else "Touchez la pochette pour la remplacer",
            style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(6.dp),
        )
        Text(t.pathSegments.joinToString(" › "), style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
        Text("${app.library.stats.count(t.uri)} lecture(s)", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)

        @Composable fun field(label: String, v: String, number: Boolean = false, set: (String) -> Unit) = OutlinedTextField(
            v, set, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(12.dp),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
        field("Titre", title) { title = it }
        field("Artiste", artist) { artist = it }
        field("Album", album) { album = it }
        field("Artiste de l'album", albumArtist) { albumArtist = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { field("Année", year, true) { year = it } }
            Column(Modifier.weight(1f)) { field("N° de piste", track, true) { track = it } }
        }
        field("Genre", genre) { genre = it }

        Text("Paroles (.lrc)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
        OutlinedTextField(
            lyrics ?: "", { lyrics = it; lyricsChanged = true }, placeholder = { Text("Collez des paroles, ou cherchez-les") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
        )
        TextButton(onClick = {
            busy = "Recherche des paroles…"
            scope.launch {
                val info = TrackInfo(title, artist, album.ifBlank { null }, durationSec = t.durationMs?.let { (it / 1000).toInt() })
                val found = runCatching { LrclibClient().find(info) }.getOrNull()
                busy = null
                if (found == null) Toast.makeText(context, "Aucune parole trouvée", Toast.LENGTH_SHORT).show()
                else { lyrics = LrcFormat.build(info, found); lyricsChanged = true }
            }
        }, contentPadding = PaddingValues(0.dp)) { Text("Rechercher sur LRCLIB") }

        Button(
            enabled = busy == null && title.isNotBlank() && artist.isNotBlank(),
            onClick = {
                busy = "Enregistrement…"
                scope.launch {
                    val info = TrackInfo(
                        title.trim(), artist.trim(), album.ifBlank { null }, albumArtist.ifBlank { null },
                        year.toIntOrNull(), track.toIntOrNull(), genre.ifBlank { null },
                    )
                    val msg = try {
                        files.save(t, info, newCover, if (lyricsChanged) lyrics?.ifBlank { null } else null)
                        "Infos enregistrées"
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        "Échec : ${e.userMessage()}"
                    }
                    busy = null
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    if (msg == "Infos enregistrées") onClose()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp),
        ) { Text("Enregistrer") }
        Spacer(Modifier.height(80.dp))
    }
}

