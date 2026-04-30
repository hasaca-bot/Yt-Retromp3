package code.name.monkey.retromusic.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

data class YoutubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: Long,
    val url: String
)

object YoutubeSearchService {

    fun init() {
        NewPipe.init(DownloaderImpl.getInstance())
    }

    suspend fun search(query: String): List<YoutubeTrack> = withContext(Dispatchers.IO) {
        try {
            val youtube = ServiceList.YouTube
            val searchExtractor = youtube.getSearchExtractor(query)
            searchExtractor.fetchPage()

            val results = mutableListOf<YoutubeTrack>()
            for (item in searchExtractor.initialPage.items) {
                if (item is StreamInfoItem) {
                    results.add(
                        YoutubeTrack(
                            videoId = item.url.substringAfter("v=").substringBefore("&"),
                            title = item.name,
                            artist = item.uploaderName ?: "Bilinmiyor",
                            thumbnailUrl = item.thumbnails.firstOrNull()?.url ?: "",
                            duration = item.duration,
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
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, videoUrl)
            streamInfo.audioStreams.maxByOrNull { it.averageBitrate }?.content
        } catch (e: Exception) {
            null
        }
    }
}
