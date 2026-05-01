package code.name.monkey.retromusic.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import code.name.monkey.retromusic.network.YoutubeSearchService
import code.name.monkey.retromusic.network.YoutubeTrack
import kotlinx.coroutines.*
import java.io.*

class YoutubeDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    private val CHANNEL_ID = "youtube_download_channel"

    companion object {
        fun startDownload(context: Context, track: YoutubeTrack) {
            val intent = Intent(context, YoutubeDownloadService::class.java).apply {
                putExtra("video_id", track.videoId)
                putExtra("title", track.title)
                putExtra("artist", track.artist)
                putExtra("thumbnail", track.thumbnailUrl)
                putExtra("url", track.url)
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: return START_NOT_STICKY
        val artist = intent.getStringExtra("artist") ?: "Unknown"
        val thumbnail = intent.getStringExtra("thumbnail") ?: ""
        val url = intent.getStringExtra("url") ?: return START_NOT_STICKY

        startForeground(startId, buildNotification(title, 0))

        serviceScope.launch {
            downloadTrack(title, artist, thumbnail, url, startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadTrack(
        title: String, artist: String,
        thumbnailUrl: String, videoUrl: String, notifId: Int
    ) {
        try {
            val streamUrl = try {
                YoutubeSearchService.getAudioStreamUrl(videoUrl)
            } catch (e: Exception) {
                null
            }
            if (streamUrl == null) {
                showErrorNotification(title)
                return
            }

            val musicDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC
                ), "RetroMusic/Downloads"
            ).also { it.mkdirs() }

            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_")
            val outputFile = File(musicDir, "$safeTitle.m4a")

            downloadFile(streamUrl, outputFile) { progress ->
                notificationManager.notify(notifId, buildNotification(title, progress))
            }

            scanFile(outputFile)
            showCompleteNotification(title)
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(title)
        }
    }

    private suspend fun downloadFile(
        url: String, outputFile: File, onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.youtube.com/")
            .header("Origin", "https://www.youtube.com")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")

        val body = response.body ?: throw Exception("Empty response")
        val fileSize = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    if (fileSize > 0) onProgress((downloaded * 100 / fileSize).toInt())
                }
            }
        }
    }

    private fun scanFile(file: File) {
        sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
            data = android.net.Uri.fromFile(file)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music Download", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(title: String, progress: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading: $title")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

    private fun showCompleteNotification(title: String) {
        notificationManager.notify(99,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloaded ✓")
                .setContentText(title)
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    private fun showErrorNotification(title: String) {
        notificationManager.notify(98,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed")
                .setContentText(title)
                .setAutoCancel(true)
                .build()
        )
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
