package code.name.monkey.retromusic.network

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

data class YoutubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: Long,
    val url: String
)

object YoutubeSearchService {

    fun init(context: Context) {
        YoutubeDL.getInstance().init(context)
        YoutubeDL.getInstance().updateYoutubeDL(context) // güncel tut
    }

    suspend fun search(query: String): List<YoutubeTrack> = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            // ytsearch ile arama
            val request = YoutubeDLRequest("ytsearch15:$query")
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            request.addOption("--skip-download")

            val response = YoutubeDL.getInstance().execute(request)
            val results = mutableListOf<YoutubeTrack>()

            response.out.trim().lines().forEach { line ->
                try {
                    val obj = org.json.JSONObject(line)
                    results.add(YoutubeTrack(
                        videoId = obj.optString("id"),
                        title = obj.optString("title"),
                        artist = obj.optString("uploader"),
                        thumbnailUrl = obj.optString("thumbnail"),
                        duration = obj.optLong("duration"),
                        url = obj.optString("webpage_url")
                    ))
                } catch (e: Exception) { }
            }
            results
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun getAudioStreamUrl(videoUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = YoutubeDLRequest(videoUrl)
            request.addOption("-f", "bestaudio")
            request.addOption("--get-url")
            val response = YoutubeDL.getInstance().execute(request)
            response.out.trim()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun downloadTrack(
        context: Context,
        videoUrl: String,
        outputDir: String,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(videoUrl)
        request.addOption("-f", "bestaudio")
        request.addOption("-x") // ses dosyasına çevir
        request.addOption("--audio-format", "mp3")
        request.addOption("--audio-quality", "0")
        request.addOption("--embed-thumbnail") // kapak fotoğrafı
        request.addOption("--add-metadata") // albüm bilgisi
        request.addOption("-o", "$outputDir/%(title)s.%(ext)s")

        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
            onProgress(progress)
        }
    }
}
