package fr.offlinetune.ui.download

import fr.offlinetune.ui.theme.rounded
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.offlinetune.App
import fr.offlinetune.core.link.LinkParser
import fr.offlinetune.core.link.ParsedInput
import fr.offlinetune.core.model.OfflineTuneException
import fr.offlinetune.download.DlState
import fr.offlinetune.download.DownloadItem
import fr.offlinetune.ui.theme.Ink

/** What the field contains, shown as a chip while typing. */
private fun detect(text: String): String? {
    if (text.isBlank()) return null
    return try {
        when (val p = LinkParser.parse(text)) {
            is ParsedInput.Link -> "Lien ${p.platform.label}"
            is ParsedInput.ShortLink -> "Lien ${p.platform.label}"
            is ParsedInput.Query -> "Recherche"
        }
    } catch (e: OfflineTuneException) {
        e.userMessage()
    }
}

@Composable
fun DownloadScreen(padding: PaddingValues, sharedText: String?, onSharedConsumed: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val model = app.downloadModel
    val phase by model.phase.collectAsState()
    val input by model.input.collectAsState()
    val downloads by app.downloads.items.collectAsState()
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val recognize by app.recognizer.state.collectAsState()
    val identified by app.recognizer.identified.collectAsState()
    val pendingClips by app.recognizer.pending.collectAsState()
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) app.recognizer.listen()
    }
    // A song just identified: search it on every platform right away.
    LaunchedEffect(recognize) {
        (recognize as? fr.offlinetune.recognize.RecognizeState.Found)?.let { model.submit(it.recognition.query); app.recognizer.dismiss() }
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) { model.submit(sharedText); onSharedConsumed() }
    }
    BackHandler(enabled = phase is DlPhase.Editing || phase is DlPhase.Sources || phase is DlPhase.Federated || phase is DlPhase.Error || phase is DlPhase.SongList) { model.back() }
    val tracks by app.library.tracks.collectAsState()
    val forYou by model.forYou.collectAsState()
    LaunchedEffect(tracks.size) { if (tracks.isNotEmpty()) model.loadForYou(tracks) }

    // Listening without downloading ends when leaving the Télécharger page.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { app.preview.stop() } }
    val current = phase
    Box(Modifier.fillMaxSize()) {
        DownloadContent(padding, current, model, app, input, downloads, recognize, identified, pendingClips, forYou, keyboard, context, micPermission)
        // The sheet has its own « Aperçu » card and its Télécharger button at the bottom.
        if (current !is DlPhase.Editing) PreviewBar(Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding()))
    }
}

@Composable
private fun DownloadContent(
    padding: PaddingValues, current: DlPhase, model: DownloadModel, app: App, input: String, downloads: List<DownloadItem>,
    recognize: fr.offlinetune.recognize.RecognizeState, identified: List<fr.offlinetune.recognize.Identified>, pendingClips: Int,
    forYou: ForYouState, keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?, context: Context,
    micPermission: androidx.activity.compose.ManagedActivityResultLauncher<String, Boolean>,
) {
    if (current is DlPhase.Editing) {
        SheetScreen(padding, current.edit, model, onBack = { model.back() })
        return
    }
    if (current is DlPhase.SongList) {
        SongListScreen(padding, current.title, current.subtitle, current.songs, onBack = { model.back() }, onSong = { model.openAnySong(it) })
        return
    }
    if (current is DlPhase.Sources) {
        SourcesScreen(padding, current.song, current.sources, onBack = { model.back() }, onChoose = { model.chooseSource(it) })
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Text("Télécharger", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 12.dp))
            // Glass search field.
            fr.offlinetune.ui.glass.GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), rounded(20.dp), strong = true) {
                androidx.compose.material3.TextField(
                    value = input, onValueChange = { model.input.value = it },
                    placeholder = { Text("Lien ou « artiste titre »", color = Ink.Muted) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Ink.Muted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); model.submit() }),
                    trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = { model.reset() }) { Icon(Icons.Rounded.Close, "Effacer") } },
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val chip = remember(input) { detect(input) }
                if (chip != null) fr.offlinetune.ui.glass.GlassChip(chip, selected = false) {}
                Spacer(Modifier.weight(1f))
                fr.offlinetune.ui.glass.GlassSurface(shape = RoundedCornerShape(50)) {
                    Row(
                        Modifier.clickable {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!text.isNullOrBlank()) { keyboard?.hide(); model.submit(text) }
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Coller", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.width(8.dp))
                fr.offlinetune.ui.glass.GlassSurface(shape = RoundedCornerShape(50)) {
                    Row(
                        Modifier.clickable {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED) app.recognizer.listen()
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Identifier", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.layout.Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (input.isNotBlank()) androidx.compose.ui.graphics.Color.White else Ink.SurfaceHigh)
                        .clickable(enabled = input.isNotBlank()) { keyboard?.hide(); model.submit() }
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                ) { Text("Rechercher", color = if (input.isNotBlank()) Ink.Background else Ink.Muted, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            }
        }

        if (recognize !is fr.offlinetune.recognize.RecognizeState.Idle) item { RecognizeCard(recognize) { app.recognizer.dismiss() } }
        if (pendingClips > 0) item {
            Text("$pendingClips son(s) enregistré(s) : identification dès que le téléphone sera connecté.",
                color = Ink.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
        when (current) {
            is DlPhase.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 16.dp))
                    Text(current.what, color = Ink.Muted)
                }
            }
            is DlPhase.Error -> item {
                Text(current.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            }
            is DlPhase.Federated -> item { SearchResults(current.outcome, current.filter, { model.setFilter(it) }, { model.openSong(it) }) }
            else -> item {
                ForYouHome(forYou, onSong = { model.openAnySong(it) }, onAlbum = { model.openAlbum(it) }, onPlaylist = { model.openSpotifyPlaylist(it) })
            }
        }

        if (identified.isNotEmpty() && current !is DlPhase.Federated) {
            item { Text("Sons identifiés", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)) }
            items(identified.take(10), key = { it.recordedAt }) { idf ->
                Row(Modifier.fillMaxWidth().clickable { model.submit(idf.recognition.query) }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(idf.recognition.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(idf.recognition.artist, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted, maxLines = 1)
                    }
                    TextButton(onClick = { model.submit(idf.recognition.query) }) { Text("Rechercher") }
                    IconButton(onClick = { app.recognizer.forget(idf) }) { Icon(Icons.Rounded.Close, "Retirer") }
                }
            }
        }
        if (downloads.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Téléchargements", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (downloads.any { it.state is DlState.Done || it.state is DlState.Failed }) {
                        TextButton(onClick = { app.downloads.clearFinished() }) { Text("Effacer les terminés") }
                    }
                }
            }
            items(downloads, key = { it.id }) { DownloadRow(it) { app.downloads.retry(it.id) } }
        }
    }
}

@Composable
private fun RecognizeCard(state: fr.offlinetune.recognize.RecognizeState, onClose: () -> Unit) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(0.85f, 1.15f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    fr.offlinetune.ui.glass.GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), rounded(22.dp), strong = true) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val listening = state is fr.offlinetune.recognize.RecognizeState.Listening
            androidx.compose.foundation.layout.Box(
                Modifier.size(52.dp).graphicsLayerScale(if (listening) pulse else 1f).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, null, tint = MaterialTheme.colorScheme.onPrimary) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val (title, sub) = when (state) {
                    is fr.offlinetune.recognize.RecognizeState.Listening -> "J'écoute…" to "Approchez le téléphone de la source (${state.secondsLeft} s)"
                    fr.offlinetune.recognize.RecognizeState.Identifying -> "Identification…" to "Recherche du morceau"
                    fr.offlinetune.recognize.RecognizeState.NotFound -> "Morceau non reconnu" to "Réessayez plus près de la source, pendant un passage chanté"
                    fr.offlinetune.recognize.RecognizeState.SavedOffline -> "Enregistré" to "Pas de connexion : le son sera identifié automatiquement dès le retour du réseau"
                    is fr.offlinetune.recognize.RecognizeState.Error -> "Erreur" to state.message
                    is fr.offlinetune.recognize.RecognizeState.Found -> "Trouvé" to "${state.recognition.artist} — ${state.recognition.title}"
                    fr.offlinetune.recognize.RecognizeState.Idle -> "" to ""
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            }
            if (!listening && state !is fr.offlinetune.recognize.RecognizeState.Identifying) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fermer") }
        }
    }
}

private fun Modifier.graphicsLayerScale(s: Float) = this.then(androidx.compose.ui.Modifier.graphicsLayer(scaleX = s, scaleY = s))

@Composable
private fun DownloadRow(item: DownloadItem, onRetry: () -> Unit) {
    fr.offlinetune.ui.glass.GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), rounded(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                when (val st = item.state) {
                    is DlState.Done -> Icon(Icons.Rounded.CheckCircle, "Terminé", tint = MaterialTheme.colorScheme.primary)
                    is DlState.Failed -> Icon(Icons.Rounded.ErrorOutline, "Échec", tint = MaterialTheme.colorScheme.error)
                    is DlState.Running -> Text("${(st.progress * 100).toInt()} %", style = MaterialTheme.typography.labelMedium, color = Ink.Muted)
                    else -> {}
                }
            }
            Spacer(Modifier.height(8.dp))
            when (val s = item.state) {
                DlState.Queued -> Text("En attente", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                is DlState.Running -> LinearProgressIndicator(
                    progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(rounded(3.dp)),
                    trackColor = fr.offlinetune.ui.glass.Glass.Fill,
                )
                is DlState.Done -> Column {
                    Text("Enregistré dans « ${s.folderName} »", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                    s.note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                }
                is DlState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text("Réessayer") }
                }
            }
        }
    }
}
