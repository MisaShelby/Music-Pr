package fr.offlinetune.ui.settings

import fr.offlinetune.ui.theme.rounded
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.search.custom.GithubLinks
import fr.offlinetune.core.search.custom.InvalidManifest
import fr.offlinetune.core.search.custom.MANIFEST_FILE
import fr.offlinetune.core.search.custom.SourceManifest
import fr.offlinetune.settings.AppSettings
import fr.offlinetune.settings.CustomSourceEntry
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.launch

/** Bundled examples (assets/sources/<file>). */
private val EXAMPLES = listOf("audius.json" to "Audius — gratuit, sans clé", "jamendo.json" to "Jamendo — clé gratuite à créer")

private val TEMPLATE = """
{
  "name": "Nom de la source",
  "search": "https://api.exemple.org/search?q={q}&key={key}",
  "results": "$.data",
  "id": "$.id",
  "title": "$.title",
  "artist": "$.artist.name",
  "album": "$.album",
  "duration": "$.duration",
  "cover": "$.cover",
  "audio": "https://api.exemple.org/tracks/{id}/stream",
  "format": "mp3",
  "quality": "MP3 320 kb/s",
  "key": false
}
""".trimIndent()

/**
 * « Sources perso »: paste a GitHub link whose repository contains a `musiquepr-source.json` describing a music API.
 * Only that description is read; no code from the repository is ever run.
 */
@Composable
fun CustomSourcesScreen(padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val s by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showFormat by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    fun add(origin: String, text: String, m: SourceManifest) = scope.launch {
        app.settings.update { st ->
            val others = st.customSources.filterNot { it.link == origin || runCatching { SourceManifest.parse(it.manifest, fallbackName = fr.offlinetune.core.search.custom.GithubLinks.repoName(it.link)).name }.getOrNull() == m.name }
            st.copy(customSources = others + CustomSourceEntry(origin, text))
        }
        message = "« ${m.name} » ajoutée" + if (m.needsKey) " : saisissez sa clé ci-dessous." else ". Elle apparaît dans la recherche (filtre « Sources perso »)."
    }

    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Retour") }
            Text("Sources perso", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            "Collez le lien d'un dépôt GitHub qui contient un fichier $MANIFEST_FILE. Ce fichier décrit une API de musique " +
                "(adresse de recherche, champs, lien audio) ; l'appli ne lit que lui et n'exécute aucun code du dépôt.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = link, onValueChange = { link = it; message = null }, singleLine = true,
            placeholder = { Text("https://github.com/compte/depot") },
            shape = rounded(14.dp), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = link.isNotBlank() && !busy, onClick = {
                busy = true; message = null
                scope.launch {
                    try {
                        val (text, m) = GithubLinks.fetch(link.trim())
                        add(link.trim(), text, m); link = ""
                    } catch (e: InvalidManifest) {
                        message = e.message
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        message = e.userMessage()
                    } finally { busy = false }
                }
            }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter") }
            if (busy) { Spacer(Modifier.width(12.dp)); CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }

        Text("Mes sources", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        if (s.customSources.isEmpty()) Text("Aucune source pour l'instant.", color = Ink.Muted)
        s.customSources.forEach { e ->
            val m = runCatching { SourceManifest.parse(e.manifest, fallbackName = fr.offlinetune.core.search.custom.GithubLinks.repoName(e.link)) }.getOrNull()
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 6.dp), rounded(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Extension, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m?.name ?: "Source illisible", style = MaterialTheme.typography.titleMedium)
                            Text(m?.quality ?: "", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                        }
                        Switch(checked = e.enabled, onCheckedChange = { on ->
                            scope.launch { app.settings.update { st -> st.copy(customSources = st.customSources.map { if (it.link == e.link) it.copy(enabled = on) else it }) } }
                        })
                        IconButton(onClick = {
                            scope.launch { app.settings.update { st -> st.copy(customSources = st.customSources.filterNot { it.link == e.link }) } }
                        }) { Icon(Icons.Rounded.Delete, "Retirer") }
                    }
                    m?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp)) }
                    Text(if (e.link.startsWith("asset:")) "Exemple intégré" else e.link, style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp))
                    if (m?.needsKey == true) {
                        var key by remember(e.link) { mutableStateOf(e.key ?: "") }
                        OutlinedTextField(
                            value = key, onValueChange = { v ->
                                key = v
                                scope.launch { app.settings.update { st -> st.copy(customSources = st.customSources.map { if (it.link == e.link) it.copy(key = v.trim().ifEmpty { null }) else it }) } }
                            },
                            label = { Text("Clé de ${m.name}") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        Text("Exemples prêts à l'emploi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        EXAMPLES.forEach { (file, label) ->
            OutlinedButton(onClick = {
                runCatching {
                    val text = context.assets.open("sources/$file").bufferedReader().use { it.readText() }
                    add("asset:$file", text, SourceManifest.parse(text))
                }.onFailure { message = it.message }
            }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(label) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Écrire sa propre source", style = MaterialTheme.typography.titleLarge)
            androidx.compose.material3.TextButton(onClick = { showFormat = !showFormat }) { Text(if (showFormat) "Masquer" else "Voir le format") }
        }
        if (showFormat) {
            Text(
                "Mettez ce fichier à la racine du dépôt (ou dans un dossier, puis collez le lien de ce dossier). " +
                    "{q} = mots recherchés, {key} = votre clé, {id} = l'identifiant du morceau ; $.champ.sous_champ lit une valeur dans chaque résultat. " +
                    "Seule l'adresse de recherche est obligatoire : titre, artiste, pochette, durée et lien audio sont trouvés automatiquement dans les réponses si vous ne les indiquez pas. « playable » (facultatif) écarte les morceaux non disponibles.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
            )
            GlassSurface(Modifier.fillMaxWidth().padding(vertical = 8.dp), rounded(14.dp)) {
                Text(TEMPLATE, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText(MANIFEST_FILE, TEMPLATE))
            }) { Text("Copier le modèle") }
            Text(
                "Les services protégés (Spotify, Deezer, Apple Music, Tidal, Amazon) ne fournissent pas de lien audio direct : ils ne peuvent pas être ajoutés ainsi.",
                style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
