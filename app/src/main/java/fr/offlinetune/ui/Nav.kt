package fr.offlinetune.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import fr.offlinetune.App
import fr.offlinetune.ui.glass.AppBackdrop
import fr.offlinetune.ui.glass.GlassNavBar
import fr.offlinetune.ui.glass.NavItem
import fr.offlinetune.ui.download.DownloadScreen
import fr.offlinetune.ui.library.LibraryScreen
import fr.offlinetune.ui.player.MiniPlayer
import fr.offlinetune.ui.player.PlayerScreen
import fr.offlinetune.ui.settings.SettingsScreen
import fr.offlinetune.ui.sound.SoundScreen

private val DESTS = listOf(
    NavItem("Bibliothèque", Icons.Rounded.LibraryMusic),
    NavItem("Rechercher", Icons.Rounded.Search),
    NavItem("Télécharger", Icons.Rounded.Download),
    NavItem("Son", Icons.Rounded.Equalizer),
    NavItem("Réglages", Icons.Rounded.Settings),
)

/** Cover key of the playing item: cover.jpg from the library, else the session artwork, else the embedded picture. */
@Composable
fun currentCoverKey(app: App): String? {
    val state by app.player.state.collectAsState()
    val tracks by app.library.tracks.collectAsState()
    val id = state.mediaId ?: return null
    val t = tracks.firstOrNull { it.uri == id }
    return Covers.key(t?.coverUri ?: state.artworkUri?.toString(), id)
}

@Composable
fun AppRoot(app: App, sharedText: String?, onSharedConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    val state by app.player.state.collectAsState()
    val tracks by app.library.tracks.collectAsState()
    val coverKey = currentCoverKey(app)
    var pendingShare by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { app.player.connect() }
    LaunchedEffect(sharedText) {
        if (sharedText != null) { pendingShare = sharedText; tab = 2; playerOpen = false; onSharedConsumed() }
    }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    AppBackdrop(coverKey, MaterialTheme.colorScheme.primary) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = fr.offlinetune.ui.theme.Ink.Text,
            bottomBar = {
                Column {
                    if (state.mediaId != null) {
                        MiniPlayer(state, coverKey, onOpen = { playerOpen = true }, onToggle = { app.player.togglePlay() }, onNext = { app.player.next() }, onSeek = { app.player.seekTo(it) })
                    }
                    GlassNavBar(DESTS, tab) { tab = it }
                }
            },
        ) { padding ->
            when (tab) {
                0 -> LibraryScreen(padding)
                1 -> LibraryScreen(padding, searchMode = true)
                2 -> DownloadScreen(padding, pendingShare) { pendingShare = null }
                3 -> SoundScreen(padding)
                else -> SettingsScreen(padding)
            }
        }
        AnimatedVisibility(playerOpen && state.mediaId != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PlayerScreen(app, state, coverKey, tracks.firstOrNull { it.uri == state.mediaId }?.lrcUri, onClose = { playerOpen = false }, onOpenSound = { tab = 3; playerOpen = false })
        }
    }
}
