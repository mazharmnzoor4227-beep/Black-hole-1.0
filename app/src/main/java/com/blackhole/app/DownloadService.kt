package com.blackhole.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import kotlinx.coroutines.*

class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
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
        if(intent?.action == "cancel") { OnDeviceExtractor.cancel(); job?.cancel(); return START_NOT_STICKY }
        if(job != null && job?.isCompleted == false) return START_NOT_STICKY
        val link = Links.extract(intent?.getStringExtra("url")) ?: run { stopSelf(); return START_NOT_STICKY }
        if(Build.VERSION.SDK_INT >= 29) startForeground(1, notification("Analyzing video"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, notification("Analyzing video"))
        Transfer.update(TransferState(Phase.ANALYZING))
        job = scope.launch {
            try {
                MediaFiles.recover(this@DownloadService)
                val downloaded = OnDeviceExtractor.download(this@DownloadService, link) { percent, detail ->
                    Transfer.update(TransferState(Phase.DOWNLOADING, percent, detail))
                    if(Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        getSystemService(NotificationManager::class.java).notify(1, notification("Downloading video", percent))
                    }
                }
                ensureActive()
                Transfer.update(TransferState(Phase.SAVING, 99, "SAVING VIDEO"))
                val quality = MediaFiles.quality(downloaded.file)
                val size = downloaded.file.length()
                val uri = MediaFiles.publish(this@DownloadService, downloaded.file, downloaded.metadata.title)
                // A failed history insertion must not turn a saved file into a download failure.
                val historyOk = runCatching { HistoryStore(this@DownloadService).use { it.add(uri.toString(), downloaded.metadata, quality, size) } }.isSuccess
                Transfer.update(TransferState(Phase.COMPLETE, 100, "$quality · ${Formatter.formatFileSize(this@DownloadService, size)}", if(historyOk) "" else "SAVED; HISTORY COULD NOT BE UPDATED", uri.toString()))
            } catch(e: CancellationException) {
                Transfer.update(TransferState(Phase.ERROR, message="DOWNLOAD CANCELLED. TAP TO RETRY"))
            } catch(e: Exception) {
                val message = when(e) {
                    is UserFailure -> e.message ?: "DOWNLOAD FAILED. TAP TO RETRY"
                    is SecurityException -> "STORAGE ACCESS DENIED"
                    else -> "DOWNLOAD FAILED. CHECK CONNECTION AND STORAGE"
                }
                Transfer.update(TransferState(Phase.ERROR, message=message))
            } finally {
                OnDeviceExtractor.cleanup(this@DownloadService)
                withContext(NonCancellable + Dispatchers.Main) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { job?.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { OnDeviceExtractor.cancel(); scope.cancel(); super.onDestroy() }
}
