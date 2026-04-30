package code.name.monkey.retromusic.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YoutubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val thumbnailUrl: String,
    val url: String
)

object YoutubeSearchService {

    private var isInitialized = false

    fun init() {
        if (isInitialized) return
        
        NewPipe.init(object : Downloader() {
            private val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            override fun execute(request: Request): Response {
                val reqBuilder = okhttp3.Request.Builder().url(request.url())
                
                request.headers().forEach { (key, list) ->
                    list.forEach { reqBuilder.addHeader(key, it) }
                }

                // YouTube bot korumasını aşmak için
                reqBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                reqBuilder.header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

                val okHttpResponse = client.newCall(reqBuilder.build()).execute()
                val body = okHttpResponse.body?.string() ?: ""
                
                return Response(
                    okHttpResponse.code,
                    okHttpResponse.message,
                    okHttpResponse.headers.toMultimap(),
                    body,
                    request.url()
                )
            }
        })
        isInitialized = true
    }

    suspend fun search(query: String): List<YoutubeTrack> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) init()
            
            val searchInfo = SearchInfo.getInfo(ServiceList.YouTube, ServiceList.YouTube.searchQHFactory.fromQuery(query))
            val results = mutableListOf<YoutubeTrack>()
            
            for (item in searchInfo.relatedItems) {
                if (item is StreamInfoItem) {
                    // 1. Video ID'sini linkin içinden söküp alıyoruz
                    val vidId = item.url.substringAfter("v=").substringBefore("&")
                    
                    results.add(
                        YoutubeTrack(
                            videoId = vidId,
                            title = item.name,
                            artist = item.uploaderName,
                            duration = item.duration ?: 0L,
                            // 2. Python kodundaki gibi küçük resmi kendimiz üretiyoruz! Hata riskini sıfırladık.
                            thumbnailUrl = "https://i.ytimg.com/vi/$vidId/hqdefault.jpg",
                            url = item.url
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getAudioStreamUrl(videoUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) init()
            
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            
            // En yüksek ses kalitesini ('bestaudio/best') seçiyoruz
            val bestAudio = streamInfo.audioStreams.maxByOrNull { it.bitrate }
            
            bestAudio?.content
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
