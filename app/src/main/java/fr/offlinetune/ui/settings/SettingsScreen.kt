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
            scope.launch { app.settings.update { it.copy(libraryTreeUri = uri.toString()) }; app.library.refreshAsync() }
        }
    }
    val pickDownload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(uri); update { it.copy(downloadTreeUri = uri.toString()) } }
    }

    var showCustom by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showCustom) { CustomSourcesScreen(padding) { showCustom = false }; return }
    var showLook by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showLook) { androidx.activity.compose.BackHandler { showLook = false }; AppearanceScreen(padding) { showLook = false }; return }
    var showLock by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showLock) { androidx.activity.compose.BackHandler { showLock = false }; LockLookScreen(padding) { showLock = false }; return }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("Réglages", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))

        Group("Affichage")
        Clickable(
            "Apparence de l'appli",
            (fr.offlinetune.settings.LOOK_PRESETS.firstOrNull { it.look.copy(textScale = s.look.textScale) == s.look }?.name ?: "Personnalisée") +
                " · couleurs, fond, effet verre, coins, taille du texte",
        ) { showLook = true }
        Clickable(
            "Écran de verrouillage",
            (if (s.lockScreenPlayer) "Activé" else "Désactivé") + " · heure, widget, affichage de la chanson",
        ) { showLock = true }

        Group("Sources")
        Clickable(
            "Sources perso (dépôts GitHub)",
            if (s.customSources.isEmpty()) "Ajouter une source de musique depuis un lien" else "${s.customSources.count { it.enabled }} active(s) sur ${s.customSources.size}",
        ) { showCustom = true }

        Group("Dossiers")
        Clickable("Dossier de bibliothèque", folderLabel(s.libraryTreeUri)) { pickLibrary.launch(null) }
        Clickable(
            "Dossier de téléchargement",
            if (s.downloadTreeUri == null) "Même dossier que la bibliothèque" else folderLabel(s.downloadTreeUri),
        ) { pickDownload.launch(null) }

        Group("Lecture")
        if (!s.autoMix) Toggle("Fondu enchaîné", "Le morceau suivant commence pendant que le précédent s'efface.", s.crossfadeEnabled) { v -> update { it.copy(crossfadeEnabled = v) } }
        if (s.crossfadeEnabled && !s.autoMix) {
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
        Toggle(
            "Mix auto DJ",
            "Enchaîne comme un DJ : tempo détecté, temps alignés, morceau suivant calé sur le tempo, échange des basses. La durée est choisie automatiquement.",
            s.autoMix,
        ) { v -> update { it.copy(autoMix = v) } }
        Toggle("Fondu à la pause et à la reprise", "Le son baisse doucement au lieu de s'arrêter net.", s.pauseFade) { v -> update { it.copy(pauseFade = v) } }
        val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> update { it.copy(resumeOnBluetooth = ok) } }
        Toggle(
            "Lecture à la connexion Bluetooth",
            "Quand une enceinte, un casque ou la voiture se connecte, la dernière chanson de cette appli reprend " +
                "(sauf si une autre musique joue déjà). Les boutons Bluetooth commandent alors cette appli.",
            s.resumeOnBluetooth,
        ) { v ->
            if (v && android.os.Build.VERSION.SDK_INT >= 31 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            else update { it.copy(resumeOnBluetooth = v) }
        }

        Group("Paroles")
        LyricsFillRow(app)

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
        Spacer(Modifier.height(8.dp))
        if (s.spotifyTokens == null) {
            Clickable("Se connecter à Spotify", "Pour afficher et télécharger les titres de vos playlists Spotify (nécessite le Client ID ci-dessus)") {
                fr.offlinetune.spotify.SpotifyLogin.start(context)
            }
        } else {
            Clickable("Connecté à Spotify", "Toucher pour se déconnecter") { update { it.copy(spotifyTokens = null) } }
        }
        KeyField("AudD — clé (Identifier un son)", s.auddToken, secret = true) { v -> update { it.copy(auddToken = v) } }
        Text("Clé gratuite sur dashboard.audd.io ; elle sert uniquement à reconnaître un son enregistré par le micro.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
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
    // Local text is the source of truth while typing (keying it on the stored value dropped the first character).
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(value ?: "") }
    androidx.compose.runtime.LaunchedEffect(value) { if (text.isEmpty() && !value.isNullOrEmpty()) text = value }
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

/** « Ajouter les paroles à toute la bibliothèque », with its progress and result. */
@Composable
private fun LyricsFillRow(app: App) {
    val st by app.lyricsFiller.state.collectAsState()
    val tracks by app.library.tracks.collectAsState()
    val without = tracks.count { it.lrcUri == null }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text("Paroles pour toute la bibliothèque", style = MaterialTheme.typography.titleMedium)
        Text(
            when (val x = st) {
                is fr.offlinetune.library.LyricsFillState.Running -> "Recherche sur LRCLIB… ${x.done} / ${x.total} · ${x.found} trouvée(s)"
                is fr.offlinetune.library.LyricsFillState.Finished ->
                    "Terminé : ${x.found} parole(s) ajoutée(s), ${x.missing} morceau(x) sans parole connue" +
                        (if (x.failed > 0) ", ${x.failed} erreur(s)" else "") + "."
                is fr.offlinetune.library.LyricsFillState.Error -> x.message
                else -> "${tracks.size - without} morceau(x) sur ${tracks.size} ont déjà des paroles. Les autres sont cherchés sur LRCLIB " +
                    "(synchronisées si possible) et enregistrés en fichier .lrc à côté de la musique."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (st is fr.offlinetune.library.LyricsFillState.Error) MaterialTheme.colorScheme.error else Ink.Muted,
        )
        val running = st is fr.offlinetune.library.LyricsFillState.Running
        if (running) {
            val r = st as fr.offlinetune.library.LyricsFillState.Running
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (r.total == 0) 1f else r.done.toFloat() / r.total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        Row(Modifier.padding(top = 6.dp)) {
            if (running) androidx.compose.material3.OutlinedButton(onClick = { app.lyricsFiller.cancel() }) { Text("Arrêter") }
            else androidx.compose.material3.Button(onClick = { app.lyricsFiller.start() }, enabled = without > 0) {
                Text(if (without > 0) "Chercher les paroles ($without morceaux)" else "Tous les morceaux ont des paroles")
            }
        }
    }
}
