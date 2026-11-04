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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import fr.offlinetune.App
import fr.offlinetune.ui.download.DownloadScreen
import fr.offlinetune.ui.library.LibraryScreen
import fr.offlinetune.ui.player.MiniPlayer
import fr.offlinetune.ui.player.PlayerScreen
import fr.offlinetune.ui.settings.SettingsScreen
import fr.offlinetune.ui.sound.SoundScreen

private data class Dest(val label: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest("Bibliothèque", Icons.Rounded.LibraryMusic),
    Dest("Télécharger", Icons.Rounded.Download),
    Dest("Son", Icons.Rounded.Equalizer),
    Dest("Réglages", Icons.Rounded.Settings),
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
        if (sharedText != null) { pendingShare = sharedText; tab = 1; playerOpen = false; onSharedConsumed() }
    }
    BackHandler(enabled = playerOpen) { playerOpen = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (state.mediaId != null) {
                        MiniPlayer(state, coverKey, onOpen = { playerOpen = true }, onToggle = { app.player.togglePlay() }, onNext = { app.player.next() })
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                        DESTS.forEachIndexed { i, d ->
                            NavigationBarItem(
                                selected = tab == i, onClick = { tab = i },
                                icon = { Icon(d.icon, null) }, label = { Text(d.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when (tab) {
                0 -> LibraryScreen(padding)
                1 -> DownloadScreen(padding, pendingShare) { pendingShare = null }
                2 -> SoundScreen(padding)
                else -> SettingsScreen(padding)
            }
        }
        AnimatedVisibility(playerOpen && state.mediaId != null, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            PlayerScreen(app, state, coverKey, tracks.firstOrNull { it.uri == state.mediaId }?.lrcUri, onClose = { playerOpen = false })
        }
    }
}
