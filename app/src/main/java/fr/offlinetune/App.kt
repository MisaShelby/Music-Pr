package fr.offlinetune

import android.app.Application
import fr.offlinetune.library.LibraryRepository
import fr.offlinetune.playback.PlayerConnection
import fr.offlinetune.settings.SettingsStore

/** Simple service locator: one instance of each long-lived component. */
class App : Application() {
    lateinit var settings: SettingsStore
        private set
    val player by lazy { PlayerConnection(this) }
    val library by lazy { LibraryRepository(this, settings) }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
    }
}
