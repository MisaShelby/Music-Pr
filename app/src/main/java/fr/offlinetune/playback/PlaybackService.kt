package fr.offlinetune.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import fr.offlinetune.App
import fr.offlinetune.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Background playback: notification, lock screen and headset/Bluetooth buttons come from the MediaSession. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var engine: CrossfadeController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        engine = CrossfadeController(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, engine.sessionPlayer).setSessionActivity(open).build()
        // Play counts (Plus écoutés / Récents), checked every second while playing.
        val library = (application as App).library
        val counter = fr.offlinetune.library.PlayCounter(library.stats)
        var lastId: String? = null
        scope.launch {
            while (true) {
                val p = engine.main
                val id = p.currentMediaItem?.mediaId
                if (id != lastId) { counter.onItem(id); lastId = id }
                if (p.isPlaying && counter.onProgress(id, p.currentPosition, p.duration)) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { library.onPlayCounted() }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
        scope.launch {
            (application as App).settings.flow.collect { s ->
                engine.settings = s
                DspState.settings = s.dsp
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        engine.release()
        super.onDestroy()
    }
}
