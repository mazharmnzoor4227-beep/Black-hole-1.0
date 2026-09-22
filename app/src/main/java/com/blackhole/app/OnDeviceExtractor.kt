package com.blackhole.app

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

data class ExtractedVideo(val file: File, val metadata: Video)

object OnDeviceExtractor {
    const val PROCESS_ID = "black-hole-download"
    private const val MIN_WORKING_SPACE = 300L * 1024L * 1024L
    private const val FORMAT = "bestvideo[vcodec^=avc1][ext=mp4]+bestaudio[acodec^=mp4a][ext=m4a]/best[vcodec^=avc1][acodec^=mp4a][ext=mp4]/best[ext=mp4]/best"

    fun cancel() {
        YoutubeDL.destroyProcessById(PROCESS_ID)
    }

    suspend fun download(
        context: Context,
        link: String,
        onProgress: (Int?, String) -> Unit,
    ): ExtractedVideo {
        currentCoroutineContext().ensureActive()
        if (context.cacheDir.usableSpace < MIN_WORKING_SPACE) throw UserFailure("NOT ENOUGH FREE STORAGE")
        cleanup(context)
        try {
            YoutubeDL.init(context.applicationContext)
            FFmpeg.init(context.applicationContext)
        } catch (e: Exception) {
            throw UserFailure("DOWNLOAD ENGINE COULD NOT START")
        }

        val infoRequest = baseRequest(link).addOption("--skip-download")
        val info = try {
            YoutubeDL.getInfo(infoRequest)
        } catch (e: Exception) {
            throw mapFailure(e)
        }
        currentCoroutineContext().ensureActive()
        val title = info.title?.takeIf { it.isNotBlank() } ?: "Video"
        val source = runCatching { URL(info.webpageUrl ?: link).host }.getOrDefault("Video")
        val advertisedQuality = when {
            info.width > 0 && info.height > 0 -> "${info.width}×${info.height}"
            !info.resolution.isNullOrBlank() -> info.resolution.orEmpty()
            else -> "BEST AVAILABLE MP4"
        }
        onProgress(0, advertisedQuality)

        val outputTemplate = File(context.cacheDir, "transfer.%(ext)s").absolutePath
        val request = baseRequest(link)
            .addOption("--output", outputTemplate)
            .addOption("--merge-output-format", "mp4")
            .addOption("--remux-video", "mp4")
            .addOption("--max-filesize", "2G")
            .addOption("--retries", 10)
            .addOption("--fragment-retries", 10)
            .addOption("--extractor-retries", 3)
            .addOption("--socket-timeout", 30)
            .addOption("--concurrent-fragments", 3)
            .addOption("--newline")
        val maximum = AtomicInteger(0)
        var lastUpdate = 0L
        try {
            YoutubeDL.execute(request, PROCESS_ID) { progress, _, _ ->
                val percent = progress.toInt().coerceIn(0, 99)
                maximum.accumulateAndGet(percent) { previous, current -> maxOf(previous, current) }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 200) {
                    lastUpdate = now
                    onProgress(maximum.get(), advertisedQuality)
                }
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw kotlinx.coroutines.CancellationException("cancelled")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw kotlinx.coroutines.CancellationException("interrupted")
        } catch (e: Exception) {
            throw mapFailure(e)
        }
        currentCoroutineContext().ensureActive()
        val file = context.cacheDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("transfer.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            .maxByOrNull { it.length() }
            ?: throw UserFailure("VIDEO DOWNLOAD DID NOT PRODUCE A FILE")
        if (file.length() <= 0L) throw UserFailure("VIDEO DOWNLOAD IS EMPTY")
        if (file.length() > MediaFiles.MAX_BYTES) throw UserFailure("VIDEO EXCEEDS THE 2 GB LIMIT")
        validateCompatibleMp4(file)
        return ExtractedVideo(file, Video(link, title, source, advertisedQuality))
    }

    fun cleanup(context: Context) {
        context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("transfer.") }
            .forEach { it.delete() }
    }

    private fun baseRequest(link: String) = YoutubeDLRequest(link)
        .addOption("--no-playlist")
        .addOption("--format", FORMAT)
        .addOption("--no-warnings")

    private fun validateCompatibleMp4(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasAvcVideo = false
            var hasAacAudio = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime == "video/avc" -> hasAvcVideo = true
                    mime.startsWith("video/") -> throw UserFailure("VIDEO CODEC IS NOT COMPATIBLE WITH THIS DEVICE")
                    mime == "audio/mp4a-latm" -> hasAacAudio = true
                    mime.startsWith("audio/") -> throw UserFailure("AUDIO CODEC IS NOT COMPATIBLE WITH THIS DEVICE")
                }
            }
            if (!hasAvcVideo) throw UserFailure("DOWNLOADED FILE DOES NOT CONTAIN COMPATIBLE VIDEO")
            if (!hasAacAudio) throw UserFailure("DOWNLOADED FILE DOES NOT CONTAIN AUDIO")
        } catch (failure: UserFailure) {
            throw failure
        } catch (_: Exception) {
            throw UserFailure("DOWNLOADED VIDEO COULD NOT BE VERIFIED")
        } finally {
            extractor.release()
        }
    }

    private fun mapFailure(error: Exception): UserFailure {
        val raw = generateSequence<Throwable>(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val message = when {
            "unsupported url" in raw -> "THIS WEBSITE OR LINK IS NOT SUPPORTED"
            "private" in raw || "login" in raw || "sign in" in raw || "cookies" in raw -> "VIDEO IS PRIVATE OR REQUIRES LOGIN"
            "drm" in raw -> "DRM-PROTECTED VIDEO CANNOT BE DOWNLOADED"
            "403" in raw || "forbidden" in raw -> "VIDEO ACCESS WAS BLOCKED. TRY ANOTHER NETWORK"
            "429" in raw || "too many requests" in raw -> "TOO MANY REQUESTS. TRY AGAIN LATER"
            "timed out" in raw || "timeout" in raw -> "CONNECTION TIMED OUT. TAP TO RETRY"
            "unable to download" in raw || "network is unreachable" in raw || "temporary failure" in raw ||
                "connection refused" in raw || "name or service not known" in raw -> "NO INTERNET OR VIDEO HOST UNREACHABLE"
            "no video formats" in raw || "requested format" in raw -> "NO COMPATIBLE VIDEO WITH AUDIO WAS FOUND"
            "max-filesize" in raw || "larger than max" in raw -> "VIDEO EXCEEDS THE 2 GB LIMIT"
            error is YoutubeDLException -> "VIDEO COULD NOT BE EXTRACTED. TRY AGAIN"
            else -> "DOWNLOAD FAILED. CHECK CONNECTION AND STORAGE"
        }
        return UserFailure(message)
    }
}
