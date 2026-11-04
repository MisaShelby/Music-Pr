package fr.offlinetune.recognize

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import fr.offlinetune.MainActivity
import fr.offlinetune.core.model.NetworkLost
import fr.offlinetune.core.model.userMessage
import fr.offlinetune.core.net.LenientJson
import fr.offlinetune.core.recognize.AudDClient
import fr.offlinetune.core.recognize.Recognition
import fr.offlinetune.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

sealed interface RecognizeState {
    data object Idle : RecognizeState
    data class Listening(val secondsLeft: Int) : RecognizeState
    data object Identifying : RecognizeState
    data class Found(val recognition: Recognition) : RecognizeState
    data object NotFound : RecognizeState
    data object SavedOffline : RecognizeState
    data class Error(val message: String) : RecognizeState
}

@Serializable
data class Identified(val recognition: Recognition, val recordedAt: Long)

@Serializable
private data class Store(val pending: List<String> = emptyList(), val identified: List<Identified> = emptyList())

/**
 * « Identifier un son »: 10 s from the microphone → AudD. Offline, the clip is kept and identified automatically
 * when the network comes back (notification + list in Télécharger).
 */
class Recognizer(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir = File(context.filesDir, "recognitions").apply { mkdirs() }
    private val storeFile = File(dir, "state.json")
    private val lock = Mutex()
    private val client = AudDClient({ latestToken })
    @Volatile private var latestToken: String? = null

    private val _state = MutableStateFlow<RecognizeState>(RecognizeState.Idle)
    val state: StateFlow<RecognizeState> = _state.asStateFlow()
    private val _store = MutableStateFlow(load())
    val pendingCount get() = _store.value.pending.size
    val identified: StateFlow<List<Identified>> get() = _identified
    private val _identified = MutableStateFlow(_store.value.identified)
    private val _pending = MutableStateFlow(_store.value.pending.size)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private fun load(): Store = runCatching { LenientJson.decodeFromString<Store>(storeFile.readText()) }.getOrDefault(Store())
    private fun save(s: Store) {
        _store.value = s; _identified.value = s.identified; _pending.value = s.pending.size
        runCatching { storeFile.writeText(LenientJson.encodeToString(s)) }
    }

    fun start() {
        scope.launch { settings.flow.collect { latestToken = it.auddToken } }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { processPending() } }
        })
        scope.launch { processPending() }
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Records [seconds] s (permission RECORD_AUDIO already granted), then identifies or keeps it for later. */
    fun listen(seconds: Int = 10) {
        if (_state.value is RecognizeState.Listening || _state.value is RecognizeState.Identifying) return
        scope.launch {
            val file = File(dir, "clip-${System.currentTimeMillis()}.m4a")
            val recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder())
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(44_100); recorder.setAudioEncodingBitRate(128_000); recorder.setAudioChannels(1)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare(); recorder.start()
                for (left in seconds downTo 1) { _state.value = RecognizeState.Listening(left); delay(1000) }
                recorder.stop()
            } catch (e: Exception) {
                file.delete()
                _state.value = RecognizeState.Error("Micro indisponible : ${e.message}")
                return@launch
            } finally {
                runCatching { recorder.release() }
            }
            if (!online()) { keep(file); return@launch }
            _state.value = RecognizeState.Identifying
            try {
                val r = client.recognize(file)
                file.delete()
                _state.value = if (r == null) RecognizeState.NotFound else { remember(r); RecognizeState.Found(r) }
            } catch (e: NetworkLost) {
                keep(file)
            } catch (e: Throwable) {
                file.delete()
                _state.value = RecognizeState.Error(e.userMessage())
            }
        }
    }

    private suspend fun keep(file: File) = lock.withLock {
        save(_store.value.copy(pending = _store.value.pending + file.name))
        _state.value = RecognizeState.SavedOffline
    }

    private suspend fun remember(r: Recognition) = lock.withLock {
        save(_store.value.copy(identified = (listOf(Identified(r, System.currentTimeMillis())) + _store.value.identified).take(50)))
    }

    /** Identifies the clips recorded offline (oldest first). */
    suspend fun processPending() {
        if (!online()) return
        val names = lock.withLock { _store.value.pending }
        for (name in names) {
            val f = File(dir, name)
            val r = try {
                if (f.exists()) withContext(Dispatchers.IO) { client.recognize(f) } else null
            } catch (e: NetworkLost) {
                return // still offline: try again at the next connection
            } catch (e: Throwable) {
                null
            }
            lock.withLock { save(_store.value.copy(pending = _store.value.pending - name)) }
            f.delete()
            if (r != null) { remember(r); notifyFound(r) }
        }
    }

    fun dismiss() { _state.value = RecognizeState.Idle }

    fun forget(item: Identified) = scope.launch { lock.withLock { save(_store.value.copy(identified = _store.value.identified - item)) } }

    private fun notifyFound(r: Recognition) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sons identifiés", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, r.hashCode(),
            Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, r.query).setType("text/plain"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(r.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Son identifié")
            .setContentText("${r.artist} — ${r.title}")
            .setContentIntent(open).setAutoCancel(true).build())
    }

    companion object { private const val CHANNEL = "recognitions" }
}
