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
    private var panel: PopupWindow? = null // retained for binary compatibility; overlay now uses one WindowManager view
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleButton: TextView? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
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
        val button = TextView(this).apply {
            text = "⬇"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0788C8.toInt())
                setStroke((2 * density).toInt(), Color.WHITE)
            }
            elevation = 14f
            contentDescription = "افتح شريط رابط التنزيل"
        }
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
        } catch (_: Exception) {
            bubble = null
            Toast.makeText(this, "تعذر إنشاء الأيقونة العائمة. تحقق من إذن الظهور فوق التطبيقات.", Toast.LENGTH_LONG).show()
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
        closePanel()
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

    /**
     * The bubble and the expanded download bar are separate WindowManager views.
     * Keeping the bubble attached prevents it from disappearing when the bar opens.
     */
    private fun showPanel() {
        if (isPanelOpen || panelView != null) return
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (22 * density).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding((14 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 34f * density
                setStroke((1 * density).toInt(), 0xFFE8EDF5.toInt())
            }
            elevation = 20f * density
        }

        val input = EditText(this).apply {
            hint = "Download"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = true
            textSize = 23f
            setTextColor(0xFF1265D8.toInt())
            setHintTextColor(0xFF1265D8.toInt())
            setSelectAllOnFocus(false)
            setPadding((12 * density).toInt(), 0, (8 * density).toInt(), 0)
            background = null
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "حقل رابط الفيديو. اضغط مطولاً للصق رابط X"
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val copied = if (clipboard.hasPrimaryClip()) clipboard.primaryClip?.getItemAt(0)
                    ?.coerceToText(this@FloatingService)?.toString().orEmpty() else ""
                if (copied.contains("x.com/") || copied.contains("twitter.com/")) setText(copied.trim())
            } catch (_: Exception) {}
        }

        val downloadButton = TextView(this).apply {
            text = "⇩"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(0xFF0878F9.toInt())
            }
            elevation = 6f * density
            isClickable = true
            isFocusable = true
            contentDescription = "تنزيل الفيديو"
        }

        card.addView(input, LinearLayout.LayoutParams(0, (88 * density).toInt(), 1f).apply {
            marginEnd = (10 * density).toInt()
        })
        card.addView(downloadButton, LinearLayout.LayoutParams((88 * density).toInt(), (88 * density).toInt()))

        val status = TextView(this).apply {
            text = "الصق رابط فيديو X ثم اضغط زر التنزيل"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF64748B.toInt())
            setPadding(0, (9 * density).toInt(), 0, 0)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(status)
        }

        val params = WindowManager.LayoutParams(
            (screenWidth - margin * 2).coerceAtLeast((260 * density).toInt()),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            windowAnimations = android.R.style.Animation_Dialog
        }

        content.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closePanel()
                true
            } else false
        }

        try {
            windowManager.addView(content, params)
            panelView = content
            panelParams = params
            linkInput = input
            statusMessage = status
            isPanelOpen = true
        } catch (_: Exception) {
            panelView = null
            panelParams = null
            isPanelOpen = false
            Toast.makeText(this, "تعذر فتح شريط التنزيل. حاول مجدداً.", Toast.LENGTH_LONG).show()
            return
        }

        val startDownload = {
            val postId = extractStatusId(input.text.toString().trim())
            if (postId == null) {
                status.text = "ألصق رابط منشور X صحيحاً أولاً"
            } else if (!isDownloading) {
                isDownloading = true
                downloadButton.isEnabled = false
                downloadButton.alpha = 0.55f
                status.text = "جارٍ استخراج الفيديو..."
                resolveVideo(postId) { result ->
                    when (result) {
                        is ResolveResult.Error -> {
                            status.text = result.message
                            isDownloading = false
                            downloadButton.isEnabled = true
                            downloadButton.alpha = 1f
                        }
                        is ResolveResult.Success -> {
                            val best = result.formats.maxWithOrNull(
                                compareBy<VideoFormat> { it.height }.thenBy { it.bitrate }
                            )
                            if (best == null) {
                                status.text = "لم نعثر على جودة قابلة للتنزيل."
                                isDownloading = false
                                downloadButton.isEnabled = true
                                downloadButton.alpha = 1f
                            } else {
                                status.text = "جارٍ تنزيل الفيديو بأفضل جودة (${best.height}p)..."
                                VideoFileDownloader.download(
                                    this,
                                    best.url,
                                    "X_${postId}_${best.height}p.mp4",
                                    "https://x.com/i/status/$postId"
                                ) { success, resultMessage ->
                                    mainHandler.post {
                                        status.text = resultMessage
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
        val view = panelView ?: return
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            linkInput?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}
        try { windowManager.removeView(view) } catch (_: Exception) {}
        panelView = null
        panelParams = null
        linkInput = null
        statusMessage = null
        isPanelOpen = false
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
