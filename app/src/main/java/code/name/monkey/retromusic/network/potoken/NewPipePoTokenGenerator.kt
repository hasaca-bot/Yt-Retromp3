/*
 * Ported from ytdlnis (https://github.com/deniscerri/ytdlnis), licensed under GPLv3.
 */
package code.name.monkey.retromusic.network.potoken

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import code.name.monkey.retromusic.App
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

class NewPipePoTokenGenerator : PoTokenProvider {
    private val supportsWebView by lazy { runCatching { CookieManager.getInstance() }.isSuccess }

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenWebView? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!supportsWebView) {
            return null
        }
        return kotlin.runCatching { getWebClientPoToken(videoId, false) }.getOrNull()
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate = webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    val innertubeClientRequestInfo = InnertubeClientRequestInfo.ofWebClient()
                    innertubeClientRequestInfo.clientInfo.clientVersion =
                        YoutubeParsingHelper.getClientVersion()

                    webPoTokenVisitorData = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        innertubeClientRequestInfo,
                        NewPipe.getPreferredLocalization(),
                        NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(),
                        YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null,
                        false
                    )

                    runBlocking {
                        webPoTokenGenerator?.let { Handler(Looper.getMainLooper()).post { it.close() } }
                        webPoTokenGenerator = PoTokenWebView.getNewPoTokenGenerator(App.getContext())
                        webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(webPoTokenVisitorData!!)
                    }
                }

                return@synchronized Quadruple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    webPoTokenStreamingPot!!,
                    shouldRecreate
                )
            }

        val playerPot = try {
            runBlocking { poTokenGenerator.generatePoToken(videoId) }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, forceRecreate = true)
            }
        }

        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String?): PoTokenResult? = null

    companion object {
        private val TAG = NewPipePoTokenGenerator::class.simpleName
    }
}
