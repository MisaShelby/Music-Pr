package fr.offlinetune.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import fr.offlinetune.core.dsp.DspSettings
import fr.offlinetune.core.net.LenientJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
data class AppSettings(
    val downloadTreeUri: String? = null,
    val libraryTreeUri: String? = null,
    val crossfadeEnabled: Boolean = false,
    val crossfadeSec: Int = 6,
    val crossfadeOnSkip: Boolean = false,
    /** « Mix automatique »: transition point and length chosen from the sound (see AutoMix). */
    val autoMix: Boolean = false,
    /** AudD token for « Identifier un son » (the user's own free key). */
    val auddToken: String? = null,
    val pauseFade: Boolean = true,
    val autoLyrics: Boolean = true,
    val dsp: DspSettings = DspSettings(),
    val userPresets: Map<String, List<Float>> = emptyMap(),
    val currentPresetName: String = "Plat",
    /** The user's own developer keys for the official Spotify / Tidal APIs (search and info only). */
    val spotifyId: String? = null,
    val spotifySecret: String? = null,
    val tidalId: String? = null,
    val tidalSecret: String? = null,
    /** Spotify account login (official PKCE): tokens, plus the verifier/state of a login in progress. */
    val spotifyTokens: fr.offlinetune.core.search.providers.SpotifyTokens? = null,
    val spotifyPkceVerifier: String? = null,
    val spotifyPkceState: String? = null,
    /** Sort / grid choice per library tab (key = tab name). */
    val tabPrefs: Map<String, TabPref> = emptyMap(),
) {
    val spotifyKeys get() = keyPair(spotifyId, spotifySecret)
    val tidalKeys get() = keyPair(tidalId, tidalSecret)
    private fun keyPair(a: String?, b: String?) = if (!a.isNullOrBlank() && !b.isNullOrBlank()) a.trim() to b.trim() else null

    /** Spec §10: the download folder defaults to the library folder. */
    val effectiveDownloadTree get() = downloadTreeUri ?: libraryTreeUri
}

private val Context.dataStore by preferencesDataStore("settings")

/** All settings stored as one JSON document in DataStore. */
/** Sort / display preference of one library tab. */
@Serializable
data class TabPref(val sort: fr.offlinetune.core.library.SortKey = fr.offlinetune.core.library.SortKey.ADDED, val desc: Boolean = true, val grid: Boolean = false)

class SettingsStore(private val context: Context) {
    /** Last known library tree (non-suspending access for helpers). */
    @Volatile var latestTree: String? = null
        private set

    private val key = stringPreferencesKey("app_settings")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        (prefs[key]?.let { runCatching { LenientJson.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings())
            .also { latestTree = it.libraryTreeUri }
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(change: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val cur = prefs[key]?.let { runCatching { LenientJson.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
            prefs[key] = LenientJson.encodeToString(change(cur))
        }
    }
}
