package fr.offlinetune.core.net

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/** NewPipe [Downloader] backed by the shared OkHttp client. */
object OkHttpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Http.USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Http.client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(resp.code, resp.message, resp.headers.toMultimap(), resp.body?.string(), resp.request.url.toString())
        }
    }
}

object NewPipeInit {
    @Volatile private var done = false

    @Synchronized
    fun ensure() {
        if (done) return
        NewPipe.init(OkHttpDownloader, Localization("fr", "FR"), ContentCountry("FR"))
        done = true
    }
}
