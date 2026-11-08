package fr.offlinetune

import android.app.Application
import kotlinx.coroutines.launch
import fr.offlinetune.download.DownloadQueue
import fr.offlinetune.library.LibraryRepository
import fr.offlinetune.playback.PlayerConnection
import fr.offlinetune.settings.SettingsStore
import fr.offlinetune.ui.download.DownloadModel

/** Simple service locator: one instance of each long-lived component. */
class App : Application() {
    lateinit var settings: SettingsStore
        private set
    val player by lazy { PlayerConnection(this) }
    val library by lazy { LibraryRepository(this, settings) }
    val downloads by lazy { DownloadQueue(this, settings, library) }
    val downloadModel by lazy { DownloadModel(settings, downloads, spotifyAccount) }

    /** Latest settings, readable without suspending (kept up to date from DataStore). */
    @Volatile var latest: fr.offlinetune.settings.AppSettings = fr.offlinetune.settings.AppSettings()
        private set
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    val spotifyAccount by lazy {
        fr.offlinetune.core.search.providers.SpotifyAccount(
            clientId = { latest.spotifyId?.trim()?.ifEmpty { null } },
            load = { latest.spotifyTokens },
            save = { t -> settings.update { it.copy(spotifyTokens = t) }; latest = latest.copy(spotifyTokens = t) },
        )
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    val preview by lazy { fr.offlinetune.playback.PreviewPlayer(this) }
    val lyricsFiller by lazy { fr.offlinetune.library.LyricsFiller(this, library) }

    val recognizer by lazy { fr.offlinetune.recognize.Recognizer(this, settings) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        recognizer.start()
        appScope.launch { settings.flow.collect { latest = it } }
        // The songs known from the last run are ready before the library screen shows up.
        library.preloadAsync()
    }
}
