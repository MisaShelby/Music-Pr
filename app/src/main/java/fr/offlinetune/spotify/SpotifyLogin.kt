package fr.offlinetune.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import fr.offlinetune.App
import fr.offlinetune.core.model.OfflineTuneException
import fr.offlinetune.core.search.providers.Pkce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/** Official Spotify login (PKCE) in the browser; the result comes back through fr.offlinetune://spotify-callback. */
object SpotifyLogin {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context) {
        val app = context.applicationContext as App
        scope.launch {
            try {
                val verifier = Pkce.verifier()
                val state = UUID.randomUUID().toString()
                app.settings.update { it.copy(spotifyPkceVerifier = verifier, spotifyPkceState = state) }
                val url = app.spotifyAccount.authorizeUrl(verifier, state)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: OfflineTuneException) {
                Toast.makeText(context, e.userMessage(), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun isCallback(uri: Uri?) = uri?.scheme == "fr.offlinetune" && uri.host == "spotify-callback"

    fun handle(context: Context, uri: Uri) {
        val app = context.applicationContext as App
        scope.launch {
            val s = app.settings.current()
            val msg = try {
                val error = uri.getQueryParameter("error")
                val code = uri.getQueryParameter("code")
                when {
                    error != null -> "Connexion Spotify refusée ($error)"
                    code == null || s.spotifyPkceVerifier == null -> "Connexion Spotify interrompue, réessayez"
                    uri.getQueryParameter("state") != s.spotifyPkceState -> "Connexion Spotify invalide, réessayez"
                    else -> { app.spotifyAccount.exchange(code, s.spotifyPkceVerifier); "Connecté à Spotify" }
                }
            } catch (e: OfflineTuneException) {
                e.userMessage()
            } catch (e: Exception) {
                "Connexion Spotify impossible : ${e.message}"
            }
            app.settings.update { it.copy(spotifyPkceVerifier = null, spotifyPkceState = null) }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
}
