package fr.offlinetune.playback

import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import fr.offlinetune.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * « Lecture à la connexion Bluetooth »: a speaker, headset or car just connected → the last song of this app
 * resumes, so this app is the one playing (and the one the Bluetooth buttons then control). Nothing happens when
 * some music is already playing at that moment: the app the user started by hand is left alone.
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_A2DP_STATE) return
        if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) return
        val app = context.applicationContext as App
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val alreadyPlaying = audio.isMusicActive
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (alreadyPlaying || !app.settings.current().resumeOnBluetooth) return@launch
                if (SessionStore.load(app) == null) return@launch
                // Let the sound route to the Bluetooth device first (otherwise the phone's speaker starts).
                delay(1500)
                app.player.resume()
                delay(1500)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
    }
}
