package code.name.monkey.retromusic.network

import code.name.monkey.retromusic.network.potoken.NewPipePoTokenGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicBoolean

data class YoutubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val thumbnailUrl: String,
    val url: String
)

data class YoutubeAudioStream(
    val url: String,
    val mimeType: String,
    val fileExtension: String
)

/**
 * Online YouTube search/metadata/stream-extraction, mirroring the approach used by ytdlnis
 * (NewPipeExtractor for search + stream resolution, with a WebView-based BotGuard PoToken
 * generator so googlevideo stream URLs keep working after YouTube's anti-bot changes).
 */
object YoutubeSearchService {

    private val initialized = AtomicBoolean(false)

    fun init() {
        if (initialized.getAndSet(true)) return
        NewPipe.init(DownloaderImpl.getInstance())
        YoutubeStreamExtractor.setPoTokenProvider(NewPipePoTokenGenerator())
    }

    suspend fun search(query: String): List<YoutubeTrack> = withContext(Dispatchers.IO) {
        init()
        try {
            val youtubeService = NewPipe.getService(ServiceList.YouTube.serviceId)
            val searchInfo = SearchInfo.getInfo(
                youtubeService,
                youtubeService.searchQHFactory.fromQuery(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                    ""
                )
            )
            searchInfo.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.duration > 0 }
                .mapNotNull { toTrack(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun toTrack(item: StreamInfoItem): YoutubeTrack? {
        return try {
            val videoId = extractVideoId(item.url) ?: return null
            YoutubeTrack(
                videoId = videoId,
                title = item.name ?: "Unknown",
                artist = item.uploaderName?.removeSuffix(" - Topic") ?: "Unknown",
                duration = item.duration,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                url = item.url
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractVideoId(url: String): String? {
        return when {
            url.contains("v=") -> url.substringAfter("v=").substringBefore("&")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?")
            else -> null
        }.takeUnless { it.isNullOrBlank() }
    }

    /**
     * Resolves the best playable/downloadable audio-only stream for a video url.
     * Prefers an m4a/aac container for maximum device compatibility, falling back to
     * whichever audio format has the highest bitrate.
     */
    suspend fun getAudioStream(videoUrl: String): YoutubeAudioStream? = withContext(Dispatchers.IO) {
        init()
        try {
            val streamInfo = StreamInfo.getInfo(videoUrl)
            val candidates = streamInfo.audioStreams
                ?.filter { !it.content.isNullOrBlank() && it.bitrate > 0 && it.itag !in listOf(599, 600) }
                ?.sortedByDescending { it.bitrate }
                ?: emptyList()
            if (candidates.isEmpty()) return@withContext null

            val best = candidates.firstOrNull { it.format?.suffix == "m4a" } ?: candidates.first()
            YoutubeAudioStream(
                url = best.content!!,
                mimeType = best.format?.mimeType ?: "audio/mp4",
                fileExtension = best.format?.suffix ?: "m4a"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getAudioStreamUrl(videoUrl: String): String? = getAudioStream(videoUrl)?.url
}
