package com.blackhole.app

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class UserFailure(message: String) : IOException(message)
data class Video(val url: String, val title: String, val source: String, val quality: String)
object Network {
    fun open(raw: String): HttpURLConnection {
        var next = raw
        repeat(6) {
            val u = URL(next)
            if (u.userInfo != null || (u.protocol != "https" && !(BuildConfig.DEBUG && u.host == "10.0.2.2" && u.protocol == "http"))) throw UserFailure("USE A PUBLIC HTTPS VIDEO LINK")
            val c = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BLACK-HOLE/1.0 Android")
                setRequestProperty("Accept-Encoding", "identity")
            }
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code in listOf(301,302,303,307,308)) {
                val location = c.getHeaderField("Location"); c.disconnect()
                if (location == null) throw UserFailure("INVALID VIDEO REDIRECT")
                next = URL(u, location).toString()
            } else {
                if (code !in 200..299) { c.disconnect(); throw UserFailure(when(code) {
                    401,403 -> "VIDEO IS PRIVATE, RESTRICTED OR EXPIRED"
                    404 -> "VIDEO NOT FOUND"
                    429 -> "TOO MANY REQUESTS. TRY AGAIN LATER"
                    else -> "SERVER ERROR ($code). TRY AGAIN"
                }) }
                return c
            }
        }
        throw UserFailure("TOO MANY VIDEO REDIRECTS")
    }
    private fun json(raw: String): JSONObject {
        val c = open(raw)
        try {
            val bytes = c.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while(out.size() <= 1024 * 1024) { val n = input.read(buffer); if(n < 0) break; out.write(buffer,0,n) }
                out.toByteArray()
            }
            if (bytes.size > 1024 * 1024) throw UserFailure("INVALID SERVER RESPONSE")
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }
    suspend fun resolve(link: String): Video {
        // A direct MP4 is streamed by the phone; social pages use the optional server.
        val path = URL(link).path.lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".m4v")) return Video(link, "Video", URL(link).host, "")
        if (BuildConfig.API_URL.isBlank()) throw UserFailure("SOCIAL DOWNLOAD SERVER IS NOT CONNECTED")
        val start = json(BuildConfig.API_URL + "/v1/jobs?url=" + java.net.URLEncoder.encode(link, "UTF-8"))
        val id = start.getString("id")
        if (!Regex("[a-f0-9]{32}").matches(id)) throw UserFailure("INVALID SERVER RESPONSE")
        repeat(180) {
            currentCoroutineContext().ensureActive()
            delay(2000)
            val job = json(BuildConfig.API_URL + "/v1/jobs/" + id)
            when (job.getString("status")) {
                "ready" -> return Video(BuildConfig.API_URL + "/v1/media/" + id, job.optString("title", "Video"), URL(link).host, job.optString("quality"))
                "failed" -> throw UserFailure(job.optString("error", "VIDEO CANNOT BE DOWNLOADED").take(160))
            }
        }
        throw UserFailure("ANALYSIS TIMED OUT. TRY AGAIN")
    }
}
