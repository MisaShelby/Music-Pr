package fr.offlinetune

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import fr.offlinetune.ui.AppRoot
import fr.offlinetune.ui.Covers
import fr.offlinetune.ui.currentCoverKey
import fr.offlinetune.ui.theme.Ink
import fr.offlinetune.ui.theme.OfflineTuneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        // Only on a fresh start: after a rotation the same intent would reload the sheet and lose edits.
        if (savedInstanceState == null) handleShare(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val app = application as App
        setContent {
            val coverKey = currentCoverKey(app)
            val accent by produceState(Ink.Brass, coverKey) {
                value = coverKey?.let { key ->
                    withContext(Dispatchers.IO) {
                        val bmp = Covers.load(this@MainActivity, key, 256) ?: return@withContext null
                        val p = Palette.from(bmp).generate()
                        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.mutedSwatch
                        swatch?.rgb?.let { Color(it) }
                    }
                } ?: Ink.Brass
            }
            OfflineTuneTheme(accent) {
                AppRoot(app, sharedText) { sharedText = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && fr.offlinetune.spotify.SpotifyLogin.isCallback(intent.data)) {
            fr.offlinetune.spotify.SpotifyLogin.handle(this, intent.data!!)
            intent.data = null
            return
        }
        if (intent?.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText = it }
    }
}
