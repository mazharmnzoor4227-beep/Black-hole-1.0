package com.blackhole.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Seed the library's working extractor once per bundled version, also on app updates. */
object BundledExtractor {
    @Synchronized fun install(context: Context) {
        val prefs = context.getSharedPreferences("bundled-extractor", Context.MODE_PRIVATE)
        val directory = File(context.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}")
        val target = File(directory, YoutubeDL.ytdlpBin)
        if (prefs.getString("version", null) == BuildConfig.EXTRACTOR_VERSION && target.exists()) return
        // Preserve a newer extractor already installed by the official updater.
        val updatedVersion = YoutubeDL.version(context).orEmpty()
        if (updatedVersion > BuildConfig.EXTRACTOR_VERSION && target.exists()) {
            prefs.edit().putString("version", BuildConfig.EXTRACTOR_VERSION).commit()
            return
        }
        val bytes = context.assets.open("yt-dlp").use { it.readBytes() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == BuildConfig.EXTRACTOR_SHA256) { "Bundled extractor checksum mismatch" }
        directory.mkdirs()
        val staging = File(directory, "yt-dlp.installing")
        try {
            staging.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            prefs.edit().putString("version", BuildConfig.EXTRACTOR_VERSION).commit()
        } finally { staging.delete() }
    }
}
