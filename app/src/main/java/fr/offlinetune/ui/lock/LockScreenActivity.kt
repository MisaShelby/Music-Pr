package fr.offlinetune.ui.lock

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.offlinetune.App
import fr.offlinetune.MainActivity
import fr.offlinetune.ui.CoverImage
import fr.offlinetune.ui.currentCoverKey
import fr.offlinetune.ui.glass.AppBackdrop
import fr.offlinetune.ui.player.SpectrumCanvas
import fr.offlinetune.ui.player.SpectrumStyle
import fr.offlinetune.ui.player.coverPalette
import fr.offlinetune.ui.player.rememberLiveSpectrum
import fr.offlinetune.ui.rememberCover
import fr.offlinetune.ui.theme.Ink
import fr.offlinetune.ui.theme.OfflineTuneTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own lock screen, shown over the phone's one while music plays (setting « Écran de verrouillage »):
 * clock, cover, title, the live spectrum and the controls. Slide up to reach the phone's own unlock screen.
 */
class LockScreenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        val app = application as App
        app.player.connect()
        setContent {
            val coverKey = currentCoverKey(app)
            val bmp = rememberCover(coverKey, 256)
            val palette = remember(bmp) { coverPalette(bmp) }
            OfflineTuneTheme(palette.first()) {
                LockScreen(app, coverKey, palette, onUnlock = { finish() }, onOpenApp = ::openApp)
            }
        }
    }

    /** « Ouvrir l'appli »: asks the phone to unlock, then shows the player. */
    private fun openApp() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val go = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        if (Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = go()
            })
        } else go()
    }

    companion object {
        /** True while this screen is shown (the service does not stack a second one). */
        @Volatile var visible = false
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { super.onStop(); visible = false }
}

@Composable
private fun LockScreen(app: App, coverKey: String?, palette: List<Color>, onUnlock: () -> Unit, onOpenApp: () -> Unit) {
    val state by app.player.state.collectAsState()
    val settings by app.settings.flow.collectAsState(initial = app.latest)
    // Music closed meanwhile: nothing to show.
    LaunchedEffect(state.connected, state.mediaId) { if (state.connected && state.mediaId == null) onUnlock() }

    val randomStyle = remember(state.mediaId) { SpectrumStyle.entries.random() }
    val style = SpectrumStyle.from(settings.spectrumStyle) ?: randomStyle
    val spectrum = rememberLiveSpectrum(state.isPlaying, style.bands)

    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(1000) } }

    // Slide up to leave: the screen follows the finger, past a third of a screen it goes away.
    var pull by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(if (leaving) -3000f else pull, tween(if (leaving) 250 else 0), label = "pull",
        finishedListener = { if (leaving) onUnlock() })

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (-pull > size.height / 4f) leaving = true else pull = 0f },
                    onDragCancel = { pull = 0f },
                ) { change, dy -> change.consume(); pull = (pull + dy).coerceAtMost(0f) }
            },
    ) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = offset; alpha = 1f + (offset / 1500f).coerceIn(-1f, 0f) }) {
            AppBackdrop(coverKey, MaterialTheme.colorScheme.primary) {
                // The live spectrum fills the lower part of the screen, behind the controls.
                SpectrumCanvas(
                    spectrum, style,
                    Modifier.fillMaxWidth().height(360.dp).align(Alignment.BottomCenter).padding(horizontal = 6.dp, vertical = 90.dp),
                    palette,
                )
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(36.dp))
                    Text(SimpleDateFormat("HH:mm", Locale.FRANCE).format(now), fontSize = 76.sp, fontWeight = FontWeight.Light, color = Color.White)
                    Text(
                        SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(32.dp))
                    CoverImage(coverKey, RoundedCornerShape(24.dp), Modifier.size(210.dp).shadow(18.dp, RoundedCornerShape(24.dp)), size = 512)
                    Spacer(Modifier.height(20.dp))
                    Text(state.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(
                        listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" — "),
                        style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        LockButton(Icons.Rounded.SkipPrevious, "Précédent", 56) { app.player.previous() }
                        LockButton(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pause" else "Lecture", 80, filled = true) {
                            app.player.togglePlay()
                        }
                        LockButton(Icons.Rounded.SkipNext, "Suivant", 56) { app.player.next() }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Ouvrir l'appli", style = MaterialTheme.typography.labelLarge, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                            .clickable(onClick = onOpenApp).padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = Color.White.copy(alpha = 0.8f))
                    Text("Glissez vers le haut pour déverrouiller", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
    }
}

@Composable
private fun LockButton(icon: ImageVector, label: String, size: Int, filled: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.14f))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size((size * 0.5f).dp), tint = if (filled) Ink.Background else Color.White)
    }
}
