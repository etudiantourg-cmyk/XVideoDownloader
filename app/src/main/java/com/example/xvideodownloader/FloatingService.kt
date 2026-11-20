package com.example.xvideodownloader

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var panel: PopupWindow? = null

    override fun onCreate() {
        super.onCreate()
        startKeepAliveNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (bubble == null) showBubble()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startKeepAliveNotification() {
        val channelId = "x_video_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                channelId,
                "الزر العائم",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إبقاء زر تنزيل الفيديو متاحاً فوق التطبيقات" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("X Video Downloader")
            .setContentText("الزر العائم يعمل")
            .setOngoing(true)
            .build()
        startForeground(101, notification)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun showBubble() {
        if (bubble != null) return
        val button = TextView(this).apply {
            text = "X"
            textSize = 21f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF111827.toInt())
                setStroke(3, 0xFF38BDF8.toInt())
            }
            elevation = 10f
        }
        val params = WindowManager.LayoutParams(
            60, 60, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18
            y = 230
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f
            var startY = 0f
            var originalX = 0
            var originalY = 0
            var dragged = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY
                        originalX = params.x; originalY = params.y
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 10) dragged = true
                        params.x = (originalX - dx).coerceAtLeast(0)
                        params.y = (originalY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(button, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) showPanel()
                        return true
                    }
                }
                return false
            }
        })
        bubble = button
        windowManager.addView(button, params)
    }

    private fun showPanel() {
        if (panel?.isShowing == true) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 18)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28f
                setStroke(2, 0xFFE2E8F0.toInt())
            }
        }
        val title = TextView(this).apply {
            text = "تنزيل فيديو من X"
            textSize = 21f
            setTextColor(0xFF111827.toInt())
            setPadding(0, 0, 0, 10)
        }
        val input = EditText(this).apply {
            hint = "ألصق رابط المنشور هنا"
            setSingleLine(true)
            textSize = 14f
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@FloatingService)?.toString().orEmpty()
                if (value.contains("x.com/") || value.contains("twitter.com/")) setText(value.trim())
            }
        }
        val message = TextView(this).apply {
            text = "انسخ رابط منشور عام يحتوي على فيديو."
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setPadding(0, 10, 0, 8)
        }
        val download = Button(this).apply { text = "استخراج الفيديو وتنزيله" }
        val history = Button(this).apply { text = "فتح مجلد التنزيلات" }
        val close = Button(this).apply { text = "إغلاق" }
        box.addView(title)
        box.addView(input)
        box.addView(message)
        box.addView(download)
        box.addView(history)
        box.addView(close)
        val width = (resources.displayMetrics.widthPixels * 0.88f).toInt()
        panel = PopupWindow(box, width, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            elevation = 18f
        }
        download.setOnClickListener {
            val postUrl = input.text.toString().trim()
            val postId = extractStatusId(postUrl)
            if (postId == null) {
                message.text = "الرابط غير صحيح. استخدم رابط منشور من x.com أو twitter.com."
                return@setOnClickListener
            }
            download.isEnabled = false
            download.text = "جارٍ البحث عن الفيديو..."
            message.text = "نتصل بمصدر بيانات المنشور..."
            resolveVideo(postId) { result ->
                download.isEnabled = true
                download.text = "استخراج الفيديو وتنزيله"
                when (result) {
                    is ResolveResult.Error -> message.text = result.message
                    is ResolveResult.Success -> showQualityPicker(result.formats, postId, message, download)
                }
            }
        }
        history.setOnClickListener {
            try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                message.text = "افتح تطبيق الملفات ثم مجلد Movies/XVideoDownloader."
            }
        }
        close.setOnClickListener { panel?.dismiss() }
        panel?.showAtLocation(bubble ?: box, Gravity.CENTER, 0, 0)
    }

    private fun extractStatusId(value: String): String? {
        return try {
            val uri = Uri.parse(value)
            val host = uri.host?.lowercase().orEmpty()
            if (!(host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com"))) return null
            Regex("/status/(\\d+)").find(uri.path.orEmpty())?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private data class VideoFormat(val url: String, val height: Int, val bitrate: Long)
    private sealed class ResolveResult {
        data class Success(val formats: List<VideoFormat>) : ResolveResult()
        data class Error(val message: String) : ResolveResult()
    }

    private fun resolveVideo(statusId: String, callback: (ResolveResult) -> Unit) {
        thread {
            val result = try {
                val connection = (URL("https://api.fxtwitter.com/2/status/$statusId").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "XVideoDownloader/1.0 (Android)")
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) {
                    ResolveResult.Error("تعذر الوصول إلى المنشور (HTTP $code). قد يكون خاصاً أو غير متاح.")
                } else {
                    val root = JSONObject(body)
                    if (root.optInt("code", code) != 200) {
                        ResolveResult.Error("لم يتمكن المصدر من قراءة المنشور. تأكد أنه عام.")
                    } else {
                        val status = root.optJSONObject("status")
                        val media = status?.optJSONObject("media")
                        val videos = media?.optJSONArray("videos")
                        val found = mutableListOf<VideoFormat>()
                        if (videos != null) {
                            for (i in 0 until videos.length()) {
                                val video = videos.optJSONObject(i) ?: continue
                                val formats = video.optJSONArray("formats") ?: continue
                                for (j in 0 until formats.length()) {
                                    val item = formats.optJSONObject(j) ?: continue
                                    val container = item.optString("container", "").lowercase()
                                    val url = item.optString("url", "")
                                    val host = try { Uri.parse(url).host.orEmpty().lowercase() } catch (_: Exception) { "" }
                                    if (url.startsWith("https://") &&
                                        (container == "mp4" || url.substringBefore("?").endsWith(".mp4", true)) &&
                                        (host == "video.twimg.com" || host.endsWith(".video.twimg.com"))) {
                                        found.add(VideoFormat(url, item.optInt("height", video.optInt("height")), item.optLong("bitrate")))
                                    }
                                }
                            }
                        }
                        val unique = found.distinctBy { it.url }.sortedWith(compareByDescending<VideoFormat> { it.height }.thenByDescending { it.bitrate })
                        if (unique.isEmpty()) ResolveResult.Error("لم نعثر على ملف MP4 مباشر. قد لا يحتوي المنشور على فيديو قابل للتنزيل.")
                        else ResolveResult.Success(unique)
                    }
                }
            } catch (_: Exception) {
                ResolveResult.Error("فشل الاتصال. تحقق من الإنترنت ثم أعد المحاولة.")
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun showQualityPicker(
        formats: List<VideoFormat>,
        statusId: String,
        message: TextView,
        downloadButton: Button
    ) {
        val labels = formats.map { format ->
            val quality = if (format.height > 0) "${format.height}p" else "جودة متاحة"
            val rate = if (format.bitrate > 0) " • ${format.bitrate / 1000} kbps" else ""
            quality + rate
        }.toTypedArray()
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("اختر جودة الفيديو")
            .setItems(labels) { selected, which ->
                selected.dismiss()
                enqueueDownload(formats[which], statusId, message, downloadButton)
            }
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.window?.setType(overlayType())
        dialog.show()
    }

    private fun enqueueDownload(
        format: VideoFormat,
        statusId: String,
        message: TextView,
        downloadButton: Button
    ) {
        try {
            val request = DownloadManager.Request(Uri.parse(format.url)).apply {
                setTitle("X video $statusId")
                setDescription("جارٍ تنزيل الفيديو")
                setMimeType("video/mp4")
                if (Build.VERSION.SDK_INT >= 24) setAllowedOverMetered(true)
                setAllowedOverRoaming(false)
                addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                addRequestHeader("Referer", "https://x.com/")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_MOVIES,
                    "XVideoDownloader/X_${statusId}_${format.height}p.mp4"
                )
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            message.text = "بدأ التنزيل. ستجد الفيديو في Movies/XVideoDownloader."
            downloadButton.text = "تم إرسال طلب التنزيل"
        } catch (_: Exception) {
            message.text = "تعذر بدء التنزيل. تحقق من صلاحية التخزين ثم أعد المحاولة."
        }
    }

    override fun onDestroy() {
        try { panel?.dismiss() } catch (_: Exception) {}
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
