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
    private companion object {
        const val ACTION_CLOSE = "fr.offlinetune.CLOSE"
    }

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
        session = MediaSession.Builder(this, engine.sessionPlayer).setSessionActivity(open)
            .setCallback(sessionCallback)
            .setCustomLayout(
                listOf(
                    androidx.media3.session.CommandButton.Builder()
                        .setDisplayName("Fermer")
                        .setIconResId(fr.offlinetune.R.drawable.ic_notif_close)
                        .setSessionCommand(closeCommand)
                        .build(),
                ),
            )
            .build()
        // A crossfade makes another player the current one: the session follows it.
        engine.onSwap = { p -> session?.player = p; dirty = true }
        restoreSession()
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Keep the session saved: right away when the queue / song / repeat mode changes, and every few seconds
        // while playing (position), so reopening the app finds the same song at the same place.
        engine.addListener(object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                if (events.containsAny(
                        androidx.media3.common.Player.EVENT_TIMELINE_CHANGED, androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION,
                        androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED, androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED,
                        androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY,
                    )
                ) dirty = true
            }
        })
        scope.launch {
            var lastSave = 0L
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = System.currentTimeMillis()
                if (dirty || (engine.main.isPlaying && now - lastSave > 5000)) {
                    dirty = false
                    lastSave = now
                    saveSession()
                }
            }
        }
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

    /** « Fermer » button of the notification: the only way, with pausing then swiping it, to end the music. */
    private val closeCommand = androidx.media3.session.SessionCommand(ACTION_CLOSE, android.os.Bundle.EMPTY)

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val base = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(base.availableSessionCommands.buildUpon().add(closeCommand).build())
                .setAvailablePlayerCommands(base.availablePlayerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand, args: android.os.Bundle,
        ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
            if (customCommand.customAction == ACTION_CLOSE) {
                closeFromNotification()
                return com.google.common.util.concurrent.Futures.immediateFuture(
                    androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS),
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** « Fermer » in the notification: the music stops and the notification goes; the session stays saved for next time. */
    private fun closeFromNotification() {
        SessionStore.save(this, SessionStore.snapshot(engine.main))
        runCatching { engine.main.stop() } // stopped = no notification
        stopSelf()
    }

    /**
     * Phone going to sleep while music plays: put the app's lock screen (live spectrum) up, so it is there when
     * the screen comes back on — when the setting is on and Android allows it (« par-dessus les autres applis »).
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            val app = application as App
            if (!app.latest.lockScreenPlayer || !engine.main.isPlaying) return
            if (fr.offlinetune.ui.lock.LockScreenActivity.visible) return
            if (!android.provider.Settings.canDrawOverlays(this@PlaybackService)) return
            runCatching {
                startActivity(
                    Intent(this@PlaybackService, fr.offlinetune.ui.lock.LockScreenActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }
        }
    }

    /** Set when the queue / song changed since the last save. */
    @Volatile private var dirty = false
    private var closing = false

    /** Puts back the song, its position and the queue saved when the app was last closed (paused, not playing). */
    private fun restoreSession() {
        val saved = SessionStore.load(this) ?: return
        val p = engine.main
        if (p.mediaItemCount > 0) return
        with(SessionStore) {
            val items = saved.items.map { it.toMediaItem() }
            p.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
        }
        p.repeatMode = saved.repeatMode
        p.playWhenReady = false
        p.prepare()
        SessionStore.shuffled = saved.shuffled
        SessionStore.original = saved.original
    }

    private fun saveSession() {
        if (closing) return
        val snap = SessionStore.snapshot(engine.main)
        scope.launch(Dispatchers.IO) { SessionStore.save(this@PlaybackService, snap) }
    }

    /**
     * The app was closed (swiped away from the recent apps): the music goes on with its notification — it only
     * ends from the notification (« Fermer », or pausing then swiping it away). The session is saved anyway.
     * With nothing queued there is nothing to keep, so the service ends.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = engine.main
        if (p.mediaItemCount == 0 || p.playbackState == androidx.media3.common.Player.STATE_IDLE) {
            closeSession()
            stopSelf()
        } else {
            saveSession()
        }
    }

    private fun closeSession() {
        if (closing) return
        val snap = SessionStore.snapshot(engine.main)
        closing = true
        SessionStore.save(this, snap) // small file, written at once so it is not lost when the process ends
        session?.player?.run { runCatching { stop() } }
    }

    override fun onDestroy() {
        closeSession()
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        session?.release()
        session = null
        engine.release()
        super.onDestroy()
    }
}
