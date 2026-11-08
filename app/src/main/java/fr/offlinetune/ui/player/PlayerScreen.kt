package fr.offlinetune.ui.player

import android.content.Context
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.delay
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Fullscreen
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.offlinetune.App
import fr.offlinetune.playback.PlayerUiState
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.glass.AppBackdrop
import fr.offlinetune.ui.glass.GlassSurface
import fr.offlinetune.ui.library.formatDuration
import fr.offlinetune.ui.theme.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Opus · 143 kb/s" from the file itself (container + measured bitrate). */
fun qualityLabel(context: Context, uri: String): String? {
    val codec = when (uri.substringAfterLast('.').substringBefore('?').lowercase()) {
        "opus", "ogg" -> "Opus"; "m4a", "aac" -> "AAC"; "mp3" -> "MP3"; "flac" -> "FLAC"; "wav" -> "WAV"; else -> null
    }
    val kbps = runCatching {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(context, Uri.parse(uri)); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.div(1000) } finally { r.release() }
    }.getOrNull()
    return when {
        codec != null && kbps != null && kbps > 0 -> "$codec · $kbps kb/s"
        codec != null -> codec
        else -> null
    }
}

/** Now playing: cover with the live spectrum, the song's waveform globe behind the controls, glass bar at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    app: App, state: PlayerUiState, coverKey: String?, lrcUri: String?, onClose: () -> Unit, onOpenSound: () -> Unit,
    fullSpectrum: Boolean = false, onFullSpectrumShown: () -> Unit = {},
) {
    val context = LocalContext.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var lyricsFull by rememberSaveable { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var addToList by remember { mutableStateOf(false) }
    var lrc by remember(lrcUri) { mutableStateOf<String?>(null) }
    LaunchedEffect(lrcUri) { lrc = lrcUri?.let { app.library.readText(it) } }
    val quality by produceState<String?>(null, state.mediaId) {
        value = state.mediaId?.let { id -> withContext(Dispatchers.IO) { qualityLabel(context, id) } }
    }
    val tracks by app.library.tracks.collectAsState()
    val current = tracks.firstOrNull { it.uri == state.mediaId }
    val settings by app.settings.flow.collectAsState(initial = app.latest)
    val viz = VizMode.from(settings.playerVisual)
    var visualMenu by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Spectrum look: the saved one, or a random one per song (« Aléatoire »); the pill's arrow tries another.
    val chosen = SpectrumStyle.from(settings.spectrumStyle)
    val randomStyle = remember(state.mediaId) { SpectrumStyle.entries.random() }
    var tried by remember(state.mediaId) { mutableStateOf<SpectrumStyle?>(null) }
    val spStyle = tried ?: chosen ?: randomStyle
    // Bar colours taken from the song's cover.
    val coverBmp = fr.offlinetune.ui.rememberCover(coverKey, 128)
    val palette = remember(coverBmp) { coverPalette(coverBmp) }
    // Phone just unlocked: the spectrum fills the whole screen at once (touch to get the player back).
    var unlockFull by remember { mutableStateOf(false) }
    LaunchedEffect(fullSpectrum) { if (fullSpectrum) { unlockFull = true; lyricsFull = false; onFullSpectrumShown() } }
    val spectrum = rememberLiveSpectrum((viz != VizMode.OFF || unlockFull) && state.isPlaying && !lyricsFull, spStyle.bands)

    // After ~3 s without touching the screen the spectrum comes forward: over the whole cover
    // (Transparence des contrôles) or alone on the whole screen (Plein écran).
    var touches by remember { mutableIntStateOf(0) }
    var immersive by remember { mutableStateOf(false) }
    LaunchedEffect(viz, touches, state.isPlaying, showLyrics, showQueue, addToList, visualMenu) {
        immersive = false
        if (viz != VizMode.OFF && state.isPlaying && !showLyrics && !showQueue && !addToList && !visualMenu) {
            delay(3000)
            immersive = true
        }
    }
    BackHandler(enabled = lyricsFull) { lyricsFull = false }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (e.changes.any { it.pressed }) touches++
                }
            }
        },
    ) {
        AppBackdrop(coverKey, MaterialTheme.colorScheme.primary, Modifier.clickable(enabled = false) {}) {
            if (lyricsFull) {
                // Paroles en plein écran: nothing but the lyrics.
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    LyricsView(lrc, state.positionMs, onSeek = { app.player.seekTo(it) }, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 48.dp))
                    Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        RoundButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Lecture", 40.dp) { app.player.togglePlay() }
                        Spacer(Modifier.width(8.dp))
                        RoundButton(Icons.Rounded.FullscreenExit, "Quitter le plein écran", 40.dp) { lyricsFull = false }
                    }
                }
                return@AppBackdrop
            }
            if (viz == VizMode.FULL && !showLyrics) {
                // Plein écran: the spectrum rises from the bottom of the page and fills it, behind everything.
                SpectrumCanvas(spectrum, spStyle, Modifier.fillMaxSize().padding(horizontal = 4.dp), palette, 0.5f)
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Réduire") }
                    Text(
                        if (showLyrics) state.title else "En lecture", style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    if (showLyrics) IconButton(onClick = { lyricsFull = true }) { Icon(Icons.Rounded.Fullscreen, "Paroles en plein écran") }
                    else Spacer(Modifier.width(48.dp))
                }

                var dragging by remember { mutableStateOf<Float?>(null) }
                val dur = state.durationMs.coerceAtLeast(1)
                val waveform = rememberWaveform(context, state.mediaId, state.durationMs, drumBarsFor(state.durationMs))
                val progress = dragging ?: rememberSmoothProgress(state.positionMs, state.durationMs, state.isPlaying, state.speed)
                val onDrag: (Float) -> Unit = { dragging = it }
                val onRelease: () -> Unit = { dragging?.let { app.player.seekTo((it * dur).toLong()) }; dragging = null }
                val elapsed = formatDuration(dragging?.let { (it * dur).toLong() } ?: state.positionMs).ifEmpty { "0:00" }
                val total = formatDuration(state.durationMs).ifEmpty { "0:00" }

                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
                    if (showLyrics) {
                        // Lyrics take the cover's and the title's place.
                        LyricsView(lrc, state.positionMs, onSeek = { app.player.seekTo(it) })
                    } else {
                        val shape = RoundedCornerShape(22.dp)
                        // Transparence des contrôles: the spectrum lives behind the cover (peeking around it);
                        // when the screen is left alone it comes in front and fills the whole cover.
                        val front by androidx.compose.animation.core.animateFloatAsState(
                            if (viz == VizMode.TRANSPARENT && immersive) 1f else 0f, androidx.compose.animation.core.tween(700), label = "front",
                        )
                        if (viz == VizMode.TRANSPARENT && front < 1f) {
                            SpectrumCanvas(spectrum, spStyle, Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true), palette, 0.75f * (1f - front))
                        }
                        Box(Modifier.fillMaxSize().aspectRatio(1f, matchHeightConstraintsFirst = true).padding(if (viz == VizMode.TRANSPARENT) 14.dp else 0.dp).shadow(12.dp, shape).clip(shape)) {
                            CoverImage(coverKey, shape, Modifier.fillMaxSize(), size = 1024)
                            if (front > 0f) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * front)))
                                SpectrumCanvas(spectrum, spStyle, Modifier.fillMaxSize().padding(6.dp), palette, 0.95f * front)
                            }
                            if (viz != VizMode.OFF) {
                                SpectrumPill(
                                    style = spStyle, random = chosen == null,
                                    onPick = { v -> tried = null; scope.launch { app.settings.update { it.copy(spectrumStyle = v) } } },
                                    onNext = { tried = SpectrumStyle.entries[(spStyle.ordinal + 1) % SpectrumStyle.entries.size] },
                                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                if (!showLyrics) {
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" — "),
                        style = MaterialTheme.typography.bodyLarge, color = Ink.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Round option buttons: visualisation menu, repeat, shuffle, quality.
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OptionToggle(Icons.Rounded.GraphicEq, "Visualisation", active = viz != VizMode.OFF) { visualMenu = true }
                        androidx.compose.material3.DropdownMenu(expanded = visualMenu, onDismissRequest = { visualMenu = false }, containerColor = Ink.Surface) {
                            VizMode.entries.forEach { v ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(v.label) },
                                    trailingIcon = { if (v == viz) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                                    onClick = { visualMenu = false; scope.launch { app.settings.update { it.copy(playerVisual = v.name) } } },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    OptionToggle(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Répéter le morceau"; Player.REPEAT_MODE_ALL -> "Répéter tout"; else -> "Pas de répétition" },
                        active = state.repeatMode != Player.REPEAT_MODE_OFF,
                        caption = when (state.repeatMode) { Player.REPEAT_MODE_ONE -> "Un"; Player.REPEAT_MODE_ALL -> "Tout"; else -> null },
                    ) { app.player.cycleRepeat() }
                    Spacer(Modifier.width(14.dp))
                    OptionToggle(Icons.Rounded.Shuffle, "Aléatoire", active = state.shuffle) {
                        app.player.toggleShuffle()
                        Toast.makeText(context, if (state.shuffle) "Ordre de la liste rétabli" else "File mélangée : voir « File » pour la suite", Toast.LENGTH_SHORT).show()
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        quality?.let { q ->
                            GlassSurface(shape = RoundedCornerShape(50)) {
                                Text(q, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                    }
                }

                val transport: @Composable () -> Unit = {
                    TransportRow(
                        playing = state.isPlaying,
                        onPrev = { app.player.previous() },
                        onBack = { app.player.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) },
                        onPlay = { app.player.togglePlay() },
                        onFwd = { app.player.seekTo((state.positionMs + 10_000).coerceAtMost(state.durationMs)) },
                        onNext = { app.player.next() },
                    )
                }
                if (viz == VizMode.OFF) {
                    // Visualisation désactivée: the song's wide bars turning like a globe, the controls inside them.
                    Box(Modifier.fillMaxWidth().height(240.dp).padding(top = 10.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WaveDrum(waveform, progress, onDrag, onRelease, Modifier.fillMaxSize().padding(bottom = 6.dp), half = 9f, playedColors = palette)
                            TimeCorners(elapsed, total, dragging != null)
                        }
                        Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { transport() }
                    }
                } else {
                    // The song's waveform globe behind see-through controls; slide on it to move in the song.
                    Box(Modifier.fillMaxWidth().height(200.dp).padding(top = 10.dp)) {
                        Box(Modifier.fillMaxSize()) {
                            WaveDrum(waveform, progress, onDrag, onRelease, Modifier.fillMaxSize().padding(bottom = 6.dp))
                            TimeCorners(elapsed, total, dragging != null)
                        }
                        Box(Modifier.fillMaxSize().padding(bottom = 14.dp), contentAlignment = Alignment.Center) { transport() }
                    }
                }
                SpeedRow(state.speed) { app.player.setSpeed(it) }
                Spacer(Modifier.height(8.dp))

                GlassSurface(Modifier.fillMaxWidth().padding(bottom = 12.dp), RoundedCornerShape(24.dp), strong = true) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BarButton(Icons.Rounded.Lyrics, "Paroles", active = showLyrics) { showLyrics = !showLyrics }
                        BarButton(Icons.AutoMirrored.Rounded.QueueMusic, "File") { showQueue = true }
                        BarButton(Icons.Rounded.Equalizer, "Son", onClick = onOpenSound)
                        BarButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", enabled = current != null) { addToList = true }
                    }
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            (immersive && viz == VizMode.FULL) || unlockFull, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(600)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(250)),
        ) {
            // Touching anywhere brings the player back (any touch restarts the 3 s timer above).
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { immersive = false; unlockFull = false }) {
                SpectrumCanvas(spectrum, spStyle, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 120.dp), palette)
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = Ink.Surface) {
            QueueSheet(app, state, tracks)
        }
    }
    if (addToList && current != null) {
        AddToPlaylistSheet(app, listOf(current)) { msg ->
            addToList = false
            msg?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        }
    }
}

/** Name of the spectrum look on top of the cover; tap it to choose one (or « Aléatoire »), arrow to try the next. */
@Composable
private fun SpectrumPill(style: SpectrumStyle, random: Boolean, onPick: (String) -> Unit, onNext: () -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f))
                .clickable { menu = true }.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(22.dp), tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(style.id, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1)
                Text(if (random) "Aléatoire" else "Choisi", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.75f))
            }
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Spectre suivant", tint = Color.White) }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Ink.Surface) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Aléatoire (un par chanson)") },
                leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                trailingIcon = { if (random) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { menu = false; onPick(SpectrumStyle.RANDOM) },
            )
            SpectrumStyle.entries.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s.id) },
                    trailingIcon = { if (!random && s == style) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { menu = false; onPick(s.name) },
                )
            }
        }
    }
}

/** Queue in play order (shuffled order when « Aléatoire » is on): the current track, then what comes next. */
@Composable
private fun QueueSheet(app: App, state: PlayerUiState, tracks: List<fr.offlinetune.library.LibTrack>) {
    val byUri = remember(tracks) { tracks.associateBy { it.uri } }
    val upcoming = state.queue.withIndex().drop((state.index + 1).coerceAtLeast(0))
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("File d'attente", style = MaterialTheme.typography.headlineSmall)
                val left = upcoming.sumOf { it.value.durationMs ?: byUri[it.value.mediaId]?.durationMs ?: 0L }
                Text(
                    "${upcoming.size} à suivre" + (if (left > 0) " · ${formatDuration(left)}" else "") + (if (state.shuffle) " · aléatoire" else ""),
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Muted,
                )
            }
            OptionToggle(Icons.Rounded.Shuffle, "Aléatoire", active = state.shuffle) { app.player.toggleShuffle() }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.TextButton(onClick = { app.player.clearQueue() }, enabled = state.queue.size > 1) { Text("Vider") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(520.dp), state = listState) {
            state.queue.getOrNull(state.index)?.let { q ->
                item(key = "now") {
                    Text("En cours", style = MaterialTheme.typography.labelLarge, color = Ink.Muted, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp))
                    GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), RoundedCornerShape(18.dp), strong = true) {
                        QueueRow(q, byUri, now = true, playing = state.isPlaying, onClick = { app.player.togglePlay() }, onRemove = null)
                    }
                }
            }
            if (upcoming.isNotEmpty()) item(key = "next-title") {
                Text(if (state.shuffle) "À suivre · ordre aléatoire" else "À suivre", style = MaterialTheme.typography.labelLarge, color = Ink.Muted,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
            }
            items(upcoming.size, key = { "q" + upcoming[it].index + upcoming[it].value.mediaId }) { k ->
                val (i, q) = upcoming[k]
                QueueRow(q, byUri, now = false, playing = false, number = k + 1, onClick = { app.player.skipTo(i) }, onRemove = { app.player.removeAt(i) })
            }
        }
    }
}

@Composable
private fun QueueRow(
    q: fr.offlinetune.playback.QueueEntry, byUri: Map<String, fr.offlinetune.library.LibTrack>, now: Boolean, playing: Boolean,
    number: Int? = null, onClick: () -> Unit, onRemove: (() -> Unit)?,
) {
    val t = byUri[q.mediaId]
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number?.toString() ?: "", style = MaterialTheme.typography.labelMedium, color = Ink.Muted,
            modifier = Modifier.width(26.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Box {
            CoverImage(fr.offlinetune.ui.Covers.key(t?.coverUri ?: q.artworkUri, q.mediaId), RoundedCornerShape(10.dp), Modifier.size(50.dp), size = 128)
            if (now) Box(Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.Pause, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(q.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                color = if (now) MaterialTheme.colorScheme.primary else Ink.Text, fontWeight = if (now) FontWeight.Bold else FontWeight.Normal)
            Text(q.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
        }
        (q.durationMs ?: t?.durationMs)?.let { d ->
            Text(formatDuration(d), style = MaterialTheme.typography.labelMedium, color = Ink.Muted, modifier = Modifier.padding(horizontal = 6.dp))
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Retirer de la file", tint = Ink.Muted) }
    }
}

/**
 * Timeline: thick rounded bar that grows while dragging (time shown above the finger); tap or drag anywhere to seek.
 */
@Composable
private fun TimeBar(progress: Float, dragging: Boolean, label: String?, onDrag: (Float) -> Unit, onRelease: () -> Unit) {
    val h by androidx.compose.animation.core.animateDpAsState(if (dragging) 14.dp else 6.dp, label = "bar")
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxWidth().height(44.dp)
            .pointerInput(Unit) {
                detectTapGestures(onPress = { o ->
                    onDrag((o.x / size.width).coerceIn(0f, 1f))
                    tryAwaitRelease(); onRelease()
                })
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onDrag((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = onRelease, onDragCancel = onRelease,
                ) { change, _ -> change.consume(); onDrag((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.85f), Color.White))),
            )
        }
        if (label != null) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                val x = (maxWidth * progress - 28.dp).coerceIn(0.dp, maxWidth - 56.dp)
                GlassSurface(Modifier.padding(start = x).width(56.dp).offset(y = (-26).dp), RoundedCornerShape(50), strong = true) {
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Playback speed: ×0.5 … ×2. */
@Composable
private fun SpeedRow(current: Float, onSpeed: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        SPEEDS.forEach { sp ->
            val on = kotlin.math.abs(sp - current) < 0.01f
            Text(
                "×" + (if (sp % 1f == 0f) sp.toInt().toString() else sp.toString().replace('.', ',')),
                style = MaterialTheme.typography.labelMedium,
                color = if (on) Ink.Background else Ink.Muted,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 3.dp)
                    .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(50))
                    .clickable { onSpeed(sp) }.padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when { !enabled -> Ink.Muted.copy(alpha = 0.5f); active -> MaterialTheme.colorScheme.primary; else -> Ink.Text }
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

/** Elapsed time bottom-left, total bottom-right; the elapsed one turns bold while seeking. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TimeCorners(elapsed: String, total: String, seeking: Boolean) {
    TimeBadge(elapsed, seeking, Modifier.align(Alignment.BottomStart))
    TimeBadge(total, false, Modifier.align(Alignment.BottomEnd))
}

@Composable
private fun TimeBadge(text: String, bold: Boolean, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (bold) FontWeight.Black else FontWeight.Bold, color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.85f)).padding(horizontal = 9.dp, vertical = 3.dp))
}

/**
 * Option switch (visualisation, repeat, shuffle): a glassy ring when off; when on it lights up in the cover's
 * colour with a soft glow, and [caption] (« Tout » / « Un ») shows the repeat mode in a small tag.
 */
@Composable
private fun OptionToggle(icon: ImageVector, label: String, active: Boolean, caption: String? = null, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val bg by androidx.compose.animation.animateColorAsState(if (active) accent else Color.White.copy(alpha = 0.08f), label = "bg")
    val fg by androidx.compose.animation.animateColorAsState(if (active) Color(0xFF0B0D14) else Color.White.copy(alpha = 0.92f), label = "fg")
    val glow by androidx.compose.animation.core.animateDpAsState(if (active) 14.dp else 0.dp, label = "glow")
    val scale by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0.92f, label = "scale")
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(46.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(glow, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape)
                .background(
                    if (active) Brush.linearGradient(listOf(lerpTo(bg, Color.White, 0.25f), bg))
                    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.14f), bg)),
                )
                .border(1.dp, if (active) Color.White.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.18f), CircleShape)
                .clickable(onClickLabel = label, role = androidx.compose.ui.semantics.Role.Switch, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, Modifier.size(22.dp), tint = fg)
        }
        if (caption != null) {
            Text(
                caption, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = Color(0xFF0B0D14),
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = 6.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 5.dp),
            )
        }
    }
}

private fun lerpTo(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha,
)

/** Translucent round button (the waveform shows through it). */
@Composable
private fun RoundButton(icon: ImageVector, label: String, size: androidx.compose.ui.unit.Dp, active: Boolean = false, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) Color.Black else Color.White.copy(alpha = if (active) 0.22f else 0.12f))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(size * 0.5f),
            tint = when { filled -> Color.White; active -> MaterialTheme.colorScheme.primary; else -> Color.White })
    }
}

@Composable
private fun TransportRow(playing: Boolean, onPrev: () -> Unit, onBack: () -> Unit, onPlay: () -> Unit, onFwd: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Rounded.SkipPrevious, "Précédent", 48.dp, onClick = onPrev)
        RoundButton(Icons.Rounded.FastRewind, "Reculer de 10 s", 66.dp, filled = true, onClick = onBack)
        RoundButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Lecture", 94.dp, filled = true, onClick = onPlay)
        RoundButton(Icons.Rounded.FastForward, "Avancer de 10 s", 66.dp, filled = true, onClick = onFwd)
        RoundButton(Icons.Rounded.SkipNext, "Suivant", 48.dp, onClick = onNext)
    }
}
