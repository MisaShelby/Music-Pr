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
    val downloadModel by lazy { DownloadModel(settings, downloads) }

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

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        appScope.launch { settings.flow.collect { latest = it } }
    }
}
