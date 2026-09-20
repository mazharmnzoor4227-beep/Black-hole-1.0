package com.blackhole.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

object MediaFiles {
    const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    fun quality(file: File): String {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            if (height <= 0 || width <= 0) throw UserFailure("THIS FILE IS NOT A PLAYABLE VIDEO")
            return "${width}×${height} · MP4"
        } finally { r.release() }
    }
    suspend fun publish(context: Context, file: File, title: String): Uri {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(70).ifBlank { "Video" }
        val name = "$safe-${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BLACK HOLE")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw UserFailure("CANNOT CREATE DOWNLOAD FILE")
            context.getSharedPreferences("pending", 0).edit().putString("uri", uri.toString()).commit()
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while(true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break; out.write(buffer, 0, n) }
                    out.flush()
                } } ?: throw UserFailure("CANNOT SAVE VIDEO")
                currentCoroutineContext().ensureActive()
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                check(resolver.update(uri, values, null, null) == 1)
                return uri
            } catch(e: Exception) { resolver.delete(uri, null, null); throw e }
            finally { context.getSharedPreferences("pending", 0).edit().clear().commit() }
        }
        @Suppress("DEPRECATION") val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BLACK HOLE")
        if (!dir.exists() && !dir.mkdirs()) throw UserFailure("STORAGE PERMISSION REQUIRED")
        val destination = File(dir, name)
        try {
            file.inputStream().use { input -> destination.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while(true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break; output.write(buffer,0,n) }
            } }
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), arrayOf("video/mp4"), null)
            return FileProvider.getUriForFile(context, context.packageName + ".files", destination)
        } catch(e: Exception) { destination.delete(); throw e }
    }
    fun recover(context: Context) {
        val prefs = context.getSharedPreferences("pending", 0)
        val pending = prefs.getString("uri", null)
        if(pending != null && Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val uri = Uri.parse(pending)
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use { c ->
                    if(c.moveToFirst() && c.getInt(0) == 1) context.contentResolver.delete(uri, null, null)
                }
            }
        }
        prefs.edit().clear().commit()
        File(context.cacheDir, "transfer.part").delete()
    }
}
