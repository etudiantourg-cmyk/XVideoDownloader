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
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class FloatingService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: View? = null
    private var trashTarget: View? = null
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
            text = "⬇"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt())
                setStroke(3, Color.WHITE)
            }
            elevation = 14f
            contentDescription = "اضغط لتنزيل فيديو X"
        }
        val bubbleSize = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * resources.displayMetrics.density).toInt()
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
                        params.x = (originalX + dx).coerceAtLeast(0)
                        params.y = (originalY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(button, params) } catch (_: Exception) {}
                        if (dragged) showTrashTarget()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged && isOverTrash(event.rawX, event.rawY)) {
                            removeBubbleByDrop()
                        } else {
                            hideTrashTarget()
                            if (!dragged) onBubbleTapped()
                        }
                        return true
                    }
                }
                return false
            }
        })
        bubble = button
        windowManager.addView(button, params)
    }

    /** Display a trash target while dragging the floating button. */
    private fun showTrashTarget() {
        if (trashTarget != null) return
        val size = (72 * resources.displayMetrics.density).toInt()
        val target = TextView(this).apply {
            text = "🗑"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE11D48.toInt())
                setStroke((2 * resources.displayMetrics.density).toInt(), Color.WHITE)
            }
            elevation = 16f
            contentDescription = "اسحب هنا لإخفاء الزر العائم"
        }
        val targetParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (42 * resources.displayMetrics.density).toInt()
        }
        try { windowManager.addView(target, targetParams); trashTarget = target } catch (_: Exception) { trashTarget = null }
    }

    private fun hideTrashTarget() {
        trashTarget?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        trashTarget = null
    }

    private fun isOverTrash(rawX: Float, rawY: Float): Boolean {
        val density = resources.displayMetrics.density
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        return kotlin.math.abs(rawX - width / 2f) <= 76f * density && rawY >= height - 150f * density
    }

    private fun removeBubbleByDrop() {
        hideTrashTarget()
        // Keep the original overlay attached: PopupWindow needs a valid window token.
        bubble?.visibility = View.INVISIBLE
        Toast.makeText(this, "تم إخفاء الزر العائم. يمكنك تشغيله مجدداً من التطبيق.", Toast.LENGTH_LONG).show()
        stopSelf()
    }
    /** Tap the floating bubble to open the link bar. */
    private fun onBubbleTapped() {
        if (panel?.isShowing == true) {
            panel?.dismiss()
            return
        }
        val detectedPrefs = getSharedPreferences("detected_post", MODE_PRIVATE)
        val detectedId = detectedPrefs.getString("status_id", null)
        val detectedAt = detectedPrefs.getLong("detected_at", 0L)
        val isFresh = System.currentTimeMillis() - detectedAt < 45_000L
        if (!detectedId.isNullOrBlank() && isFresh) {
            val postUrl = "https://x.com/i/status/$detectedId"
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("X post link", postUrl))
            } catch (_: Exception) { }
            downloadDetectedPost(detectedId)
        } else {
            showPanel()
        }
    }

    private fun downloadDetectedPost(statusId: String) {
        Toast.makeText(this, "تم التعرف على المنشور؛ جارٍ استخراج الفيديو...", Toast.LENGTH_SHORT).show()
        resolveVideo(statusId) { result ->
            when (result) {
                is ResolveResult.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    showPanel()
                }
                is ResolveResult.Success -> {
                    val best = result.formats.maxWithOrNull(compareBy<VideoFormat> { it.height }.thenBy { it.bitrate })
                    if (best == null) {
                        Toast.makeText(this, "لم نعثر على فيديو قابل للتنزيل.", Toast.LENGTH_LONG).show()
                        showPanel()
                    } else {
                        VideoFileDownloader.download(this, best.url, "X_${statusId}_${best.height}p.mp4",
                            "https://x.com/i/status/$statusId") { _, message ->
                            mainHandler.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
        }
    }
    private fun showPanel() {
        if (panel?.isShowing == true) return
        val density = resources.displayMetrics.density
        // Keep the overlay attached as a valid PopupWindow anchor.
        bubble?.visibility = View.INVISIBLE
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (7 * density).toInt(), (8 * density).toInt(), (7 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 36f * density
                setStroke((1 * density).toInt(), 0xFFE2E8F0.toInt())
            }
            elevation = 16f
        }
        val bubbleButton = TextView(this).apply {
            text = "⬇"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 8f
            contentDescription = "إغلاق شريط التنزيل"
            setOnClickListener { panel?.dismiss() }
        }
        val bubbleSize = (48 * density).toInt()
        row.addView(bubbleButton, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
        val input = EditText(this).apply {
            hint = "ألصق رابط منشور X هنا"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            textSize = 16f
            setTextColor(0xFF111827.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            try {
                if (clipboard.hasPrimaryClip()) {
                    val value = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@FloatingService)?.toString().orEmpty()
                    if (value.contains("x.com/") || value.contains("twitter.com/")) setText(value.trim())
                }
            } catch (_: Exception) {}
        }
        val send = TextView(this).apply {
            text = "➜"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF1685E5.toInt())
            }
            elevation = 5f
            contentDescription = "تنزيل الفيديو"
        }
        val size = (54 * density).toInt()
        row.addView(input, LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f))
        row.addView(send, LinearLayout.LayoutParams(size, size))
        val hint = TextView(this).apply {
            text = "الصق رابط منشور X ثم اضغط السهم للتنزيل"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (5 * density).toInt(), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addView(row)
            addView(hint)
        }
        val width = resources.displayMetrics.widthPixels - (16 * density).toInt()
        panel = PopupWindow(content, width, WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            elevation = 18f
            inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setOnDismissListener {
                panel = null
                bubble?.visibility = View.VISIBLE
            }
        }
        val startDownload = {
            val postId = extractStatusId(input.text.toString().trim())
            if (postId == null) {
                hint.text = "الرابط غير صحيح. ألصق رابط منشور من X يحتوي على فيديو."
            } else {
                send.isEnabled = false
                send.alpha = 0.55f
                hint.text = "جارٍ استخراج الفيديو..."
                resolveVideo(postId) { result ->
                    when (result) {
                        is ResolveResult.Error -> {
                            hint.text = result.message
                            send.isEnabled = true
                            send.alpha = 1f
                        }
                        is ResolveResult.Success -> {
                            val best = result.formats.maxWithOrNull(compareBy<VideoFormat> { it.height }.thenBy { it.bitrate })
                            if (best == null) {
                                hint.text = "لم نعثر على جودة قابلة للتنزيل."
                                send.isEnabled = true
                                send.alpha = 1f
                            } else {
                                hint.text = "جارٍ تنزيل الفيديو بأفضل جودة (${best.height}p)..."
                                VideoFileDownloader.download(
                                    this,
                                    best.url,
                                    "X_${postId}_${best.height}p.mp4",
                                    "https://x.com/i/status/$postId"
                                ) { success, message ->
                                    mainHandler.post {
                                        hint.text = message
                                        send.isEnabled = true
                                        send.alpha = 1f
                                        if (success) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        send.setOnClickListener { startDownload() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startDownload()
                true
            } else false
        }
        val attachedAnchor = bubble
        if (attachedAnchor == null || !attachedAnchor.isAttachedToWindow) {
            panel = null
            showBubble()
            return
        }
        try {
            panel?.showAtLocation(attachedAnchor, Gravity.TOP or Gravity.START, (8 * density).toInt(), (resources.displayMetrics.heightPixels * 0.62f).toInt())
        } catch (e: Exception) {
            panel = null
            attachedAnchor.visibility = View.VISIBLE
            Toast.makeText(this, "تعذر إظهار شريط الرابط. أعد الضغط على الأيقونة.", Toast.LENGTH_LONG).show()
            return
        }
        input.requestFocus()
        input.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
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
            message.text = "جارٍ الاتصال بخادم الفيديو..."
            downloadButton.isEnabled = false
            VideoFileDownloader.download(
                this,
                format.url,
                "X_${statusId}_${format.height}p.mp4",
                "https://x.com/i/status/$statusId"
            ) { success, resultMessage ->
                mainHandler.post {
                    message.text = resultMessage
                    downloadButton.isEnabled = true
                    downloadButton.text = if (success) "اكتمل التنزيل" else "إعادة المحاولة"
                }
            }
        } catch (_: Exception) {
            message.text = "تعذر بدء التنزيل. تحقق من صلاحية التخزين ثم أعد المحاولة."
        }
    }

    override fun onDestroy() {
        try { panel?.dismiss() } catch (_: Exception) {}
        hideTrashTarget()
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
