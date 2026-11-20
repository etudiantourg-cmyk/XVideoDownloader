package com.example.xvideodownloader

import android.content.ContentValues
import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams public X videos into Movies/XVideoDownloader.
 * If a signed CDN URL has expired, refresh it once from FxTwitter and retry.
 * No account cookies are collected or sent.
 */
object VideoFileDownloader {
    fun download(
        context: Context,
        videoUrl: String,
        fileName: String,
        referer: String,
        callback: (Boolean, String) -> Unit
    ) {
        Thread {
            var outputUri: Uri? = null
            var activeConnection: HttpURLConnection? = null
            try {
                val initialUri = Uri.parse(videoUrl)
                require(isTrustedMediaUrl(initialUri)) { "رابط الفيديو غير موثوق." }

                if (Build.VERSION.SDK_INT < 29) {
                    val request = DownloadManager.Request(initialUri)
                        .setTitle(fileName)
                        .setMimeType("video/mp4")
                        .addRequestHeader("User-Agent", USER_AGENT)
                        .addRequestHeader("Referer", "https://x.com/")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "XVideoDownloader/$fileName")
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    callback(true, "أُرسل طلب التنزيل إلى مدير التنزيلات.")
                    return@Thread
                }

                var currentUrl = videoUrl
                var result = openMedia(currentUrl)
                if (result.first == null && result.second == 403) {
                    // video.twimg.com URLs are signed and can expire. Re-resolve the
                    // post to obtain a fresh URL, then retry exactly once.
                    val statusId = Regex("(?:x|X)_(\\d+)_").find(fileName)?.groupValues?.get(1).orEmpty()
                    val desiredHeight = Regex("_(\\d+)p\\.mp4$", RegexOption.IGNORE_CASE).find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val refreshed = if (statusId.isNotEmpty()) refreshMediaUrl(statusId, desiredHeight) else null
                    if (refreshed != null && refreshed != currentUrl) {
                        currentUrl = refreshed
                        result = openMedia(currentUrl)
                    }
                }

                activeConnection = result.first
                if (activeConnection == null) {
                    val code = result.second
                    val detail = result.third
                    throw Exception(
                        if (code == 403) "رفض خادم X الوصول (HTTP 403) حتى بعد تحديث رابط الفيديو. هذا المنشور يتطلب صلاحية لا يتيحها المصدر العام أو أن CDN يحجب الاتصال."
                        else "فشل تنزيل الفيديو: HTTP $code. $detail"
                    )
                }

                val contentType = activeConnection!!.contentType?.substringBefore(";")?.lowercase().orEmpty()
                if (contentType.isNotEmpty() && !contentType.startsWith("video/") && contentType != "application/octet-stream") {
                    throw Exception("الخادم لم يُرجع ملف فيديو (نوع المحتوى: $contentType).")
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/XVideoDownloader")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("تعذر إنشاء ملف الفيديو في مساحة التخزين.")

                val output = context.contentResolver.openOutputStream(outputUri!!, "w")
                    ?: throw Exception("تعذر فتح ملف الحفظ.")
                activeConnection!!.inputStream.use { source ->
                    output.use { destination -> source.copyTo(destination, 64 * 1024) }
                }
                activeConnection!!.disconnect()
                activeConnection = null

                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(outputUri!!, done, null, null)
                callback(true, "تم تنزيل الفيديو وحفظه في Movies/XVideoDownloader.")
            } catch (error: Exception) {
                try { activeConnection?.disconnect() } catch (_: Exception) {}
                if (outputUri != null) {
                    try { context.contentResolver.delete(outputUri!!, null, null) } catch (_: Exception) {}
                }
                callback(false, error.message ?: "تعذر تنزيل الفيديو.")
            }
        }.start()
    }

    private fun isTrustedMediaUrl(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        return uri.scheme == "https" && (host == "video.twimg.com" || host.endsWith(".video.twimg.com"))
    }

    /** Returns connection, HTTP code, and a short diagnostic. */
    private fun openMedia(mediaUrl: String): Triple<HttpURLConnection?, Int, String> {
        var lastCode = -1
        var detail = ""
        for (referer in listOf("https://x.com/", "https://twitter.com/", "")) {
            val connection = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20000
                readTimeout = 45000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                if (referer.isNotEmpty()) setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
            }
            lastCode = connection.responseCode
            if (lastCode in 200..299) return Triple(connection, lastCode, "")
            detail = try {
                (connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
                    .replace(Regex("\\s+"), " ").take(160)
            } catch (_: Exception) { "" }
            connection.disconnect()
            if (lastCode != 403) break
        }
        return Triple(null, lastCode, detail)
    }

    /** Obtain a new signed MP4 URL from the public FxTwitter API. */
    private fun refreshMediaUrl(statusId: String, desiredHeight: Int): String? {
        return try {
            val connection = (URL("https://api.fxtwitter.com/2/status/$statusId").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", "XVideoDownloader/1.2 Android")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val root = JSONObject(body)
            if (root.optInt("code", code) != 200) return null
            val videos = root.optJSONObject("status")?.optJSONObject("media")?.optJSONArray("videos")
                ?: return null
            val candidates = mutableListOf<Pair<String, Int>>()
            for (i in 0 until videos.length()) {
                val video = videos.optJSONObject(i) ?: continue
                val formats = video.optJSONArray("formats") ?: continue
                for (j in 0 until formats.length()) {
                    val format = formats.optJSONObject(j) ?: continue
                    val url = format.optString("url", "")
                    val uri = Uri.parse(url)
                    val container = format.optString("container", "").lowercase()
                    if (isTrustedMediaUrl(uri) &&
                        (container == "mp4" || url.substringBefore("?").endsWith(".mp4", true))) {
                        candidates.add(url to format.optInt("height", video.optInt("height")))
                    }
                }
            }
            candidates.distinctBy { it.first }
                .sortedWith(compareBy<Pair<String, Int>> { if (desiredHeight > 0) kotlin.math.abs(it.second - desiredHeight) else 0 }
                    .thenByDescending { it.second })
                .firstOrNull()?.first
        } catch (_: Exception) {
            null
        }
    }


    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
