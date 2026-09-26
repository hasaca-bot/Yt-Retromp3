package code.name.monkey.retromusic.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"

class DownloaderImpl private constructor() : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        @Volatile
        private var instance: DownloaderImpl? = null

        fun getInstance(): DownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: DownloaderImpl().also { instance = it }
            }
        }
    }

    override fun execute(request: NpRequest): Response {
        val httpRequest = Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .addHeader("User-Agent", USER_AGENT)
            .apply {
                request.headers().forEach { (key, values) ->
                    if (values.size > 1) {
                        removeHeader(key)
                        values.forEach { value -> addHeader(key, value) }
                    } else if (values.size == 1) {
                        header(key, values[0])
                    }
                }
            }
            .build()

        val response = client.newCall(httpRequest).execute()
        if (response.code == 429) {
            response.close()
            throw ReCaptchaException("reCaptcha Challenge requested", request.url())
        }
        return Response(
            response.code,
            response.message,
            response.headers.toMultimap(),
            response.body?.string(),
            response.request.url.toString()
        )
    }
}
