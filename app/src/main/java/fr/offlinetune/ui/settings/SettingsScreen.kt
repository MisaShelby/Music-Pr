package fr.offlinetune.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.BuildConfig
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Human-readable folder from a SAF tree Uri ("primary:Music/OfflineTune" → "Music/OfflineTune"). */
fun folderLabel(uri: String?): String {
    if (uri == null) return "Non choisi"
    return runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
        val (volume, path) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val vol = if (volume == "primary") "Mémoire interne" else "Carte SD"
        if (path.isEmpty()) vol else "$vol › ${path.replace("/", " › ")}"
    }.getOrDefault(uri)
}

@Composable
fun SettingsScreen(padding: PaddingValues) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val s by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    fun update(change: (AppSettings) -> AppSettings) = scope.launch { app.settings.update(change) }

    fun persist(uri: Uri) = context.contentResolver.takePersistableUriPermission(
        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )

    val pickLibrary = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            scope.launch { app.settings.update { it.copy(libraryTreeUri = uri.toString()) }; app.library.refresh() }
        }
    }
    val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(uri); update { it.copy(downloadTreeUri = uri.toString()) } }
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Réglages", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

        Group("Dossiers")
        Clickable("Dossier de bibliothèque", folderLabel(s.libraryTreeUri)) { pickLibrary.launch(null) }
        Clickable(
            "Dossier de téléchargement",
            if (s.downloadTreeUri == null) "Même dossier que la bibliothèque" else folderLabel(s.downloadTreeUri),
        ) { pickDownload.launch(null) }

        Group("Lecture")
        Toggle("Fondu enchaîné", "Le morceau suivant commence pendant que le précédent s'efface.", s.crossfadeEnabled) { v -> update { it.copy(crossfadeEnabled = v) } }
        if (s.crossfadeEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Durée", modifier = Modifier.weight(1f))
                Text("${s.crossfadeSec} s", color = Ink.Muted)
            }
            Slider(
                value = s.crossfadeSec.toFloat(), onValueChange = { v -> update { it.copy(crossfadeSec = v.roundToInt()) } },
                valueRange = 0f..12f, steps = 11, colors = SliderDefaults.colors(inactiveTrackColor = Ink.SurfaceHigh),
            )
            Toggle("Aussi lors d'un saut manuel", "Fondu quand vous touchez Suivant ou Précédent.", s.crossfadeOnSkip) { v -> update { it.copy(crossfadeOnSkip = v) } }
        } else {
            Text("Désactivé : les morceaux s'enchaînent sans blanc.", style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Toggle("Fondu à la pause et à la reprise", "Le son baisse doucement au lieu de s'arrêter net.", s.pauseFade) { v -> update { it.copy(pauseFade = v) } }

        Group("Téléchargement")
        Toggle("Paroles automatiques", "Cherche les paroles sur LRCLIB pour chaque fiche.", s.autoLyrics) { v -> update { it.copy(autoLyrics = v) } }

        Group("Plateformes")
        Text(
            "YouTube Music, SoundCloud, Deezer et Apple Music fonctionnent sans rien faire. Pour chercher aussi dans " +
                "Spotify et Tidal, créez une clé développeur gratuite (developer.spotify.com, developer.tidal.com) " +
                "et collez-la ici. Elle ne sert qu'à la recherche et aux infos.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        KeyField("Spotify — Client ID", s.spotifyId, secret = false) { v -> update { it.copy(spotifyId = v) } }
        KeyField("Spotify — Client Secret", s.spotifySecret, secret = true) { v -> update { it.copy(spotifySecret = v) } }
        KeyField("Tidal — Client ID", s.tidalId, secret = false) { v -> update { it.copy(tidalId = v) } }
        KeyField("Tidal — Client Secret", s.tidalSecret, secret = true) { v -> update { it.copy(tidalSecret = v) } }

        Group("À propos")
        Text("Musique Pr ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Logiciel libre sous licence GPL-3.0. Sans publicité ni traqueur. Extraction : NewPipeExtractor. Paroles : LRCLIB.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun KeyField(label: String, value: String?, secret: Boolean, onChange: (String?) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable(value == null) { androidx.compose.runtime.mutableStateOf(value ?: "") }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it.trim().ifEmpty { null }) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = Ink.SurfaceHigh)
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Clickable(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
