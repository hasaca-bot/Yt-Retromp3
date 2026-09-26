package code.name.monkey.retromusic.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import code.name.monkey.retromusic.network.YoutubeSearchService
import code.name.monkey.retromusic.network.YoutubeTrack
import kotlinx.coroutines.*
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.io.FileOutputStream

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
            ContextCompat.startForegroundService(context, intent)
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
        var tempAudioFile: File? = null
        var tempArtFile: File? = null
        try {
            val stream = YoutubeSearchService.getAudioStream(videoUrl)
            if (stream == null) {
                showErrorNotification(title)
                return
            }

            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").ifBlank { "track" }
            tempAudioFile = File(cacheDir, "yt_dl_${notifId}.${stream.fileExtension}")

            downloadFile(stream.url, tempAudioFile) { progress ->
                notificationManager.notify(notifId, buildNotification(title, progress))
            }

            tempArtFile = runCatching { downloadThumbnail(thumbnailUrl, notifId) }.getOrNull()
            tagAudioFile(tempAudioFile, title, artist, tempArtFile)

            val fileName = "$safeTitle.${stream.fileExtension}"
            val inserted = insertIntoMediaStore(fileName, stream.mimeType, tempAudioFile)
            if (!inserted) {
                showErrorNotification(title)
                return
            }

            showCompleteNotification(title)
        } catch (e: Exception) {
            e.printStackTrace()
            showErrorNotification(title)
        } finally {
            tempAudioFile?.delete()
            tempArtFile?.delete()
        }
    }

    private fun tagAudioFile(file: File, title: String, artist: String, artFile: File?) {
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)
            tag.setField(FieldKey.ALBUM, "YouTube")
            if (artFile != null) {
                try {
                    tag.deleteArtworkField()
                    tag.setField(AndroidArtwork.createArtworkFromFile(artFile))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            audioFile.commit()
        } catch (e: Exception) {
            // Not every container (e.g. opus/webm) is supported for tagging;
            // keep the raw downloaded audio in that case instead of failing the download.
            e.printStackTrace()
        }
    }

    private suspend fun downloadThumbnail(url: String, notifId: Int): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val client = okhttp3.OkHttpClient.Builder().build()
        val request = okhttp3.Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null
        val body = response.body ?: return@withContext null
        val file = File(cacheDir, "yt_dl_art_${notifId}.jpg")
        body.byteStream().use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file
    }

    private fun insertIntoMediaStore(fileName: String, mimeType: String, sourceFile: File): Boolean {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/RetroMusic")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val destFile: File? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val musicDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "RetroMusic"
            ).also { it.mkdirs() }
            File(musicDir, fileName).also { values.put(MediaStore.Audio.Media.DATA, it.absolutePath) }
        } else null

        val uri: Uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        resolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val update = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
            resolver.update(uri, update, null, null)
        }

        destFile?.let {
            MediaScannerConnection.scanFile(this, arrayOf(it.absolutePath), arrayOf(mimeType), null)
        }
        return true
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
