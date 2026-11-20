package com.example.xvideodownloader

import android.content.ContentValues
import android.content.Context
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams the selected public X video into Movies/XVideoDownloader.
 * This avoids handing the request to the browser or DownloadManager on Android 10+,
 * so the request headers and response diagnostics are controlled by the app.
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
            try {
                val uri = Uri.parse(videoUrl)
                val host = uri.host?.lowercase().orEmpty()
                require(uri.scheme == "https" && (host == "video.twimg.com" || host.endsWith(".video.twimg.com"))) {
                    "رابط الفيديو غير موثوق."
                }

                // Android 9 and earlier: retain DownloadManager compatibility.
                if (Build.VERSION.SDK_INT < 29) {
                    val request = DownloadManager.Request(uri)
                        .setTitle(fileName)
                        .setMimeType("video/mp4")
                        .addRequestHeader("User-Agent", USER_AGENT)
                        .addRequestHeader("Referer", referer)
                        .addRequestHeader("Origin", "https://x.com")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "XVideoDownloader/$fileName")
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    callback(true, "أُرسل طلب التنزيل إلى مدير التنزيلات.")
                    return@Thread
                }

                // X's media CDN can reject a status-page Referer. Retry with the
                // canonical site root and finally without Referer; no cookies are used.
                val refererCandidates = listOf("https://x.com/", "https://twitter.com/", "")
                var connection: HttpURLConnection? = null
                var code = -1
                var lastDetail = ""
                for (candidate in refererCandidates) {
                    val attempt = (URL(videoUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20000
                        readTimeout = 30000
                        instanceFollowRedirects = true
                        useCaches = false
                        setRequestProperty("User-Agent", USER_AGENT)
                        if (candidate.isNotEmpty()) setRequestProperty("Referer", candidate)
                        setRequestProperty("Accept", "*/*")
                        setRequestProperty("Accept-Encoding", "identity")
                        setRequestProperty("Connection", "close")
                    }
                    code = attempt.responseCode
                    if (code in 200..299) {
                        connection = attempt
                        break
                    }
                    lastDetail = try {
                        (attempt.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
                            .replace(Regex("\\s+"), " ").take(180)
                    } catch (_: Exception) { "" }
                    attempt.disconnect()
                    if (code != 403) break
                }
                val activeConnection = connection ?: throw Exception(
                    if (code == 403) "رفض CDN الوصول حتى بعد تجربة Referer بديل وطلب بلا Referer (HTTP 403). قد يكون رابط الفيديو منتهي الصلاحية أو محجوباً من X."
                    else "فشل تنزيل الفيديو: HTTP $code. $lastDetail"
                )

                val contentType = activeConnection.contentType?.substringBefore(";")?.lowercase().orEmpty()
                if (contentType.isNotEmpty() && !contentType.startsWith("video/") && contentType != "application/octet-stream") {
                    activeConnection.disconnect()
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

                val input = activeConnection.inputStream
                val output = context.contentResolver.openOutputStream(outputUri!!, "w")
                    ?: throw Exception("تعذر فتح ملف الحفظ.")
                input.use { source ->
                    output.use { destination -> source.copyTo(destination, 64 * 1024) }
                }
                activeConnection.disconnect()

                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(outputUri!!, done, null, null)
                callback(true, "تم تنزيل الفيديو وحفظه في Movies/XVideoDownloader.")
            } catch (error: Exception) {
                if (outputUri != null) {
                    try { context.contentResolver.delete(outputUri!!, null, null) } catch (_: Exception) {}
                }
                callback(false, error.message ?: "تعذر تنزيل الفيديو.")
            }
        }.start()
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
