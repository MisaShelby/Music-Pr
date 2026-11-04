package fr.offlinetune

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }
}
