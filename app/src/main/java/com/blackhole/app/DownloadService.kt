package com.blackhole.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import kotlinx.coroutines.*
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var connection: java.net.HttpURLConnection? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String, percent: Int? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("BLACK HOLE").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(true).setProgress(100, percent ?: 0, percent == null)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == "cancel") { job?.cancel(); return START_NOT_STICKY }
        if(job?.isActive == true) return START_NOT_STICKY
        val link = Links.extract(intent?.getStringExtra("url")) ?: run { stopSelf(); return START_NOT_STICKY }
        if(Build.VERSION.SDK_INT >= 29) startForeground(1, notification("Analyzing video"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification("Analyzing video"))
        Transfer.update(TransferState(Phase.ANALYZING))
        job = scope.launch {
            val file = File(cacheDir, "transfer.part")
            try {
                MediaFiles.recover(this@DownloadService)
                val video = Network.resolve(link)
                ensureActive()
                val c = Network.open(video.url)
                connection = c
                try {
                    val type = c.contentType.orEmpty().substringBefore(';').lowercase()
                    if(type.contains("text") || type.contains("json") || type.contains("mpegurl")) throw UserFailure("LINK DOES NOT CONTAIN A DOWNLOADABLE MP4")
                    val length = c.contentLengthLong
                    if(length > MediaFiles.MAX_BYTES) throw UserFailure("VIDEO EXCEEDS THE 2 GB LIMIT")
                    if(cacheDir.usableSpace < (if(length > 0) length * 2 else 100L * 1024 * 1024)) throw UserFailure("NOT ENOUGH FREE STORAGE")
                    var total = 0L
                    var lastUpdate = 0L
                    c.inputStream.use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while(true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if(n < 0) break
                            total += n
                            if(total > MediaFiles.MAX_BYTES) throw UserFailure("VIDEO EXCEEDS THE 2 GB LIMIT")
                            output.write(buffer,0,n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if(now-lastUpdate >= 200) {
                                lastUpdate = now
                                val percent = if(length > 0) ((total * 100 / length).toInt()).coerceIn(0,99) else null
                                val detail = listOf(video.quality, Formatter.formatFileSize(this@DownloadService, total)).filter { it.isNotBlank() }.joinToString(" · ")
                                Transfer.update(TransferState(Phase.DOWNLOADING, percent, detail))
                                getSystemService(NotificationManager::class.java).notify(1, notification("Downloading video", percent))
                            }
                        }
                    } }
                    if(total == 0L || (length > 0 && total != length)) throw UserFailure("DOWNLOAD INTERRUPTED. TAP TO RETRY")
                } finally { c.disconnect(); connection = null }
                ensureActive()
                Transfer.update(TransferState(Phase.SAVING, 99, "SAVING VIDEO"))
                val quality = MediaFiles.quality(file)
                val uri = MediaFiles.publish(this@DownloadService, file, video.title)
                // A failed history insertion must not turn a saved file into a download failure.
                val historyOk = runCatching { HistoryStore(this@DownloadService).use { it.add(uri.toString(), video, quality, file.length()) } }.isSuccess
                Transfer.update(TransferState(Phase.COMPLETE, 100, "$quality · ${Formatter.formatFileSize(this@DownloadService, file.length())}", if(historyOk) "" else "SAVED; HISTORY COULD NOT BE UPDATED", uri.toString()))
            } catch(e: CancellationException) {
                Transfer.update(TransferState(Phase.ERROR, message="DOWNLOAD CANCELLED. TAP TO RETRY"))
            } catch(e: Exception) {
                val message = when(e) {
                    is UserFailure -> e.message ?: "DOWNLOAD FAILED. TAP TO RETRY"
                    is UnknownHostException -> "NO INTERNET OR SERVER UNREACHABLE"
                    is SocketTimeoutException -> "CONNECTION TIMED OUT. TAP TO RETRY"
                    is SecurityException -> "STORAGE ACCESS DENIED"
                    else -> "DOWNLOAD FAILED. CHECK CONNECTION AND STORAGE"
                }
                Transfer.update(TransferState(Phase.ERROR, message=message))
            } finally {
                file.delete()
                withContext(NonCancellable + Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { scope.cancel(); connection?.disconnect(); super.onDestroy() }
}
