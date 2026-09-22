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
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

data class ExtractedVideo(val file: File, val metadata: Video)

object OnDeviceExtractor {
    const val PROCESS_ID = "black-hole-download"
    private const val MIN_WORKING_SPACE = 300L * 1024L * 1024L
    private const val FORMAT = "bestvideo+bestaudio/best"


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
            BundledExtractor.install(context.applicationContext)
        } catch (e: Exception) {
            throw UserFailure("DOWNLOAD ENGINE COULD NOT START")
        }

        // Parse only the fields we use. Upstream metadata can contain nulls and
        // new field types that do not fit the library's fixed VideoInfo model.
        suspend fun analyze(): JSONObject = runInterruptible {
            val response = YoutubeDL.execute(
                baseRequest(link).addOption("--skip-download").addOption("--dump-single-json"),
                PROCESS_ID,
            )
            JSONObject(response.out)
        }
        val info = try {
            analyze()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            // One bounded recovery attempt, not a loop or an external API fallback.
            val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong("lastUpdateAttempt", 0L) < 6 * 60 * 60 * 1000L) {
                throw mapFailure(e)
            }
            prefs.edit().putLong("lastUpdateAttempt", now).apply()
            Transfer.update(TransferState(Phase.ANALYZING, detail = "UPDATING DOWNLOAD ENGINE"))
            try {
                runInterruptible { YoutubeDL.updateYoutubeDL(context.applicationContext) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw mapFailure(e)
            }
            currentCoroutineContext().ensureActive()
            try { analyze() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (retry: Exception) { throw mapFailure(retry) }
        }
        currentCoroutineContext().ensureActive()
        val title = info.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: "Video"
        val source = runCatching { URL(link).host }.getOrDefault("Video")
        val advertisedQuality = when {
            info.optInt("width") > 0 && info.optInt("height") > 0 -> "${info.optInt("width")}×${info.optInt("height")}"
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
            currentCoroutineContext().ensureActive()
            throw mapFailure(e)
        }
        currentCoroutineContext().ensureActive()
        // Never publish a leftover audio stream or an incomplete merge as video.
        val file = File(context.cacheDir, "transfer.mp4")
        if (!file.isFile) throw UserFailure("VIDEO DOWNLOAD DID NOT PRODUCE A FINAL MP4")
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
        .addOption("--socket-timeout", 20)
        .addOption("--extractor-retries", 2)
        .apply { if (BuildConfig.DEBUG) addOption("--verbose") }

    private fun validateCompatibleMp4(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasAvcVideo = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") -> hasAvcVideo = true
                }
            }
            if (!hasAvcVideo) throw UserFailure("DOWNLOADED FILE DOES NOT CONTAIN COMPATIBLE VIDEO")
            // A source video can legitimately be silent.
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
        val diagnostic = generateSequence<Throwable>(error) { it.cause }
            .joinToString("\n") { it.message.orEmpty() }
            .replace(Regex("https?://[^\\s]+"), "[link]")
            .takeLast(1800)
        return UserFailure(message, diagnostic)
    }
}
