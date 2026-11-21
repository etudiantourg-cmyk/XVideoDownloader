package com.example.xvideodownloader

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
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
    private var panel: PopupWindow? = null // retained for binary compatibility; overlay now uses one WindowManager view
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleButton: View? = null
    private var arrowView: TextView? = null
    private var arrowAnimator: ObjectAnimator? = null
    private var linkInput: EditText? = null
    private var statusMessage: TextView? = null
    private var isPanelOpen = false
    @Volatile private var isDownloading = false

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
        val density = resources.displayMetrics.density
        val arrow = TextView(this).apply {
            text = "↓"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
            contentDescription = "سهم تنزيل متحرك للأسفل"
        }
        val button = android.widget.FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0788C8.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 14f
            contentDescription = "افتح شريط رابط التنزيل"
            addView(arrow, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        arrowView = arrow
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(button, android.widget.FrameLayout.LayoutParams(
                (56 * density).toInt(), (56 * density).toInt(), Gravity.START or Gravity.TOP
            ))
        }
        val params = WindowManager.LayoutParams(
            (56 * density).toInt(), (56 * density).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * density).toInt()
            y = (230 * density).toInt()
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
                        startX = event.rawX
                        startY = event.rawY
                        originalX = params.x
                        originalY = params.y
                        dragged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > (8 * density).toInt()) dragged = true
                        params.x = (originalX + dx).coerceAtLeast(0)
                        params.y = (originalY + dy).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) {}
                        if (dragged) showTrashTarget()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragged && isOverTrash(event.rawX, event.rawY)) {
                            removeBubbleByDrop()
                        } else {
                            hideTrashTarget()
                            if (!dragged) showPanel()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        hideTrashTarget()
                        return true
                    }
                }
                return false
            }
        })
        try {
            windowManager.addView(root, params)
            bubble = root
            overlayParams = params
            bubbleButton = button
            startArrowAnimation()
        } catch (_: Exception) {
            bubble = null
            Toast.makeText(this, "تعذر إنشاء الأيقونة العائمة. تحقق من إذن الظهور فوق التطبيقات.", Toast.LENGTH_LONG).show()
        }
    }

    /** Subtle, continuous downward motion on the arrow only; the bubble itself stays still. */
    private fun startArrowAnimation() {
        val arrow = arrowView ?: return
        arrowAnimator?.cancel()
        val distance = 4f * resources.displayMetrics.density
        arrowAnimator = ObjectAnimator.ofFloat(arrow, View.TRANSLATION_Y, 0f, distance).apply {
            duration = 650L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
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
        bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubble = null
        bubbleButton = null
        overlayParams = null
        Toast.makeText(this, "تم إخفاء الزر العائم. يمكنك تشغيله مجدداً من التطبيق.", Toast.LENGTH_LONG).show()
        stopSelf()
    }
    /** Tap the floating bubble to open the link bar. */
    private fun onBubbleTapped() {
        showPanel()
    }

    private fun showPanel() {
        if (isPanelOpen) return
        val root = bubble as? android.widget.FrameLayout ?: return
        val params = overlayParams ?: return
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 28f * density
                setStroke((1 * density).toInt(), 0xFFD7DEE8.toInt())
            }
            elevation = 16f
        }
        val downloadButton = TextView(this).apply {
            text = "⬇"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0788C8.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 8f
            contentDescription = "اضغط لتنزيل الفيديو"
        }
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
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val copied = if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@FloatingService)?.toString().orEmpty() else ""
                if (copied.contains("x.com/") || copied.contains("twitter.com/")) setText(copied.trim())
            } catch (_: Exception) {}
        }
        row.addView(downloadButton, LinearLayout.LayoutParams((48 * density).toInt(), (48 * density).toInt()))
        row.addView(input, LinearLayout.LayoutParams(0, (54 * density).toInt(), 1f))
        val message = TextView(this).apply {
            text = "الصق رابط منشور X ثم اضغط السهم للتنزيل"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (5 * density).toInt(), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            addView(row)
            addView(message)
        }
        arrowAnimator?.cancel()
        arrowAnimator = null
        arrowView?.translationY = 0f
        root.removeAllViews()
        root.addView(content, android.widget.FrameLayout.LayoutParams(
            resources.displayMetrics.widthPixels - (16 * density).toInt(),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))
        params.width = resources.displayMetrics.widthPixels - (16 * density).toInt()
        params.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        try {
            windowManager.updateViewLayout(root, params)
            isPanelOpen = true
            linkInput = input
            statusMessage = message
            root.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    closePanel()
                    true
                } else false
            }
        } catch (_: Exception) {
            closePanel()
            Toast.makeText(this, "تعذر فتح شريط الرابط. أعد المحاولة.", Toast.LENGTH_LONG).show()
            return
        }
        val startDownload = {
            val postId = extractStatusId(input.text.toString().trim())
            if (postId == null) {
                message.text = "الرابط غير صحيح. ألصق رابط منشور X يحتوي على فيديو."
            } else if (!isDownloading) {
                isDownloading = true
                downloadButton.isEnabled = false
                downloadButton.alpha = 0.55f
                message.text = "جارٍ استخراج الفيديو..."
                resolveVideo(postId) { result ->
                    when (result) {
                        is ResolveResult.Error -> {
                            message.text = result.message
                            isDownloading = false
                            downloadButton.isEnabled = true
                            downloadButton.alpha = 1f
                        }
                        is ResolveResult.Success -> {
                            val best = result.formats.maxWithOrNull(compareBy<VideoFormat> { it.height }.thenBy { it.bitrate })
                            if (best == null) {
                                message.text = "لم نعثر على جودة قابلة للتنزيل."
                                isDownloading = false
                                downloadButton.isEnabled = true
                                downloadButton.alpha = 1f
                            } else {
                                message.text = "جارٍ تنزيل الفيديو بأفضل جودة (${best.height}p)..."
                                VideoFileDownloader.download(
                                    this,
                                    best.url,
                                    "X_${postId}_${best.height}p.mp4",
                                    "https://x.com/i/status/$postId"
                                ) { success, resultMessage ->
                                    mainHandler.post {
                                        message.text = resultMessage
                                        isDownloading = false
                                        downloadButton.isEnabled = true
                                        downloadButton.alpha = 1f
                                        if (success) Toast.makeText(this, resultMessage, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        downloadButton.setOnClickListener { startDownload() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                startDownload()
                true
            } else false
        }
        input.requestFocus()
        input.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun closePanel() {
        val root = bubble as? android.widget.FrameLayout ?: return
        val params = overlayParams ?: return
        try {
            val button = bubbleButton ?: return
            root.setOnTouchListener(null)
            root.removeAllViews()
            root.addView(button, android.widget.FrameLayout.LayoutParams(
                (56 * resources.displayMetrics.density).toInt(),
                (56 * resources.displayMetrics.density).toInt(),
                Gravity.START or Gravity.TOP
            ))
            params.width = (56 * resources.displayMetrics.density).toInt()
            params.height = (56 * resources.displayMetrics.density).toInt()
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            params.gravity = Gravity.TOP or Gravity.START
            params.x = params.x.coerceAtLeast(0)
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
            windowManager.updateViewLayout(root, params)
            startArrowAnimation()
        } catch (_: Exception) {}
        isPanelOpen = false
        linkInput = null
        statusMessage = null
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
        bubbleButton = null
        overlayParams = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
