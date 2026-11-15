package com.joabtv.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal fun nzbLink(url: String): String? {
    val prefixed = url.startsWith("nzb:", true) || url.startsWith("usenet:", true)
    val direct = if (prefixed) url.substringAfter(':').removePrefix("//") else url
    return direct.takeIf { it.startsWith("http", true) && (prefixed || it.substringBefore('?').endsWith(".nzb", true)) }
}

internal fun playbackMimeType(url: String, contentType: String = "", prefix: String = ""): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val mime = contentType.substringBefore(';').trim().lowercase()
    return when {
        path.endsWith(".m3u8") || "mpegurl" in mime || prefix.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U") -> "application/x-mpegURL"
        path.endsWith(".mpd") || mime == "application/dash+xml" || Regex("<(?:\\w+:)?MPD[\\s>]").containsMatchIn(prefix) -> "application/dash+xml"
        else -> null
    }
}

/** Alguns addons usam links sem extensão para HLS e DASH. */
internal suspend fun resolvePlaybackMimeType(url: String, headers: Map<String, String>): String? {
    playbackMimeType(url)?.let { return it }
    val path = url.substringBefore('?').lowercase()
    if (!url.startsWith("http") || listOf(".mp4", ".mkv", ".webm", ".avi", ".ts", ".mov").any { path.endsWith(it) }) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", STREAM_UA).header("Referer", STREAM_REFERER)
                .apply { headers.forEach { (key, value) -> header(key, value) } }.header("Range", "bytes=0-1023").build()
            AppDns.mediaClient.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else playbackMimeType(response.request.url.toString(), response.header("Content-Type").orEmpty(), response.peekBody(1024).string())
            }
        }.getOrNull()
    }
}
