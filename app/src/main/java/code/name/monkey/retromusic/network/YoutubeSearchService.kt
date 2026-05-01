package code.name.monkey.retromusic.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

data class YoutubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val thumbnailUrl: String,
    val url: String
)

object YoutubeSearchService {

    private val INVIDIOUS_INSTANCES = listOf(
        "https://invidious.privacydev.net",
        "https://yt.cdaut.de",
        "https://invidious.nerdvpn.de"
    )

    fun init() {
        // Invidious için init gerekmez
    }

    suspend fun search(query: String): List<YoutubeTrack> = withContext(Dispatchers.IO) {
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "$instance/api/v1/search?q=$encodedQuery&type=video"
                
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val jsonArray = JSONArray(body)
                val results = mutableListOf<YoutubeTrack>()

                for (i in 0 until minOf(jsonArray.length(), 20)) {
                    val item = jsonArray.getJSONObject(i)
                    val videoId = item.optString("videoId") ?: continue
                    val title = item.optString("title", "Unknown")
                    val author = item.optString("author", "Unknown")
                    val duration = item.optLong("lengthSeconds", 0L)

                    results.add(
                        YoutubeTrack(
                            videoId = videoId,
                            title = title,
                            artist = author,
                            duration = duration,
                            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                            url = "https://www.youtube.com/watch?v=$videoId"
                        )
                    )
                }
                if (results.isNotEmpty()) return@withContext results
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
        emptyList()
    }

    suspend fun getAudioStreamUrl(videoUrl: String): String? = withContext(Dispatchers.IO) {
        val videoId = videoUrl.substringAfter("v=").substringBefore("&")
        
        for (instance in INVIDIOUS_INSTANCES) {
            try {
                val url = "$instance/api/v1/videos/$videoId"
                
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: continue

                var bestUrl: String? = null
                var bestBitrate = 0

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val mimeType = format.optString("type", "")
                    if (!mimeType.startsWith("audio")) continue
                    
                    val bitrate = format.optInt("bitrate", 0)
                    val streamUrl = format.optString("url", "")
                    
                    if (bitrate > bestBitrate && streamUrl.isNotEmpty()) {
                        bestBitrate = bitrate
                        bestUrl = streamUrl
                    }
                }
                if (bestUrl != null) return@withContext bestUrl
            } catch (e: Exception) {
                e.printStackTrace()
                continue
            }
        }
        null
    }
}
