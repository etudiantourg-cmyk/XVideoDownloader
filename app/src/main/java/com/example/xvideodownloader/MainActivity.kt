package com.example.xvideodownloader

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var link: EditText
    private lateinit var status: TextView
    private lateinit var extract: Button
    private var waitingForOverlay = false
    private data class Format(val url: String, val height: Int, val bitrate: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }
        root.addView(TextView(this).apply {
            text = "X Video Downloader"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFF111827.toInt())
            setPadding(0, 16, 0, 10)
        })
        root.addView(TextView(this).apply {
            text = "تنزيل فيديوهات X مباشرة إلى هاتفك"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF475569.toInt())
            setPadding(0, 0, 0, 24)
        })
        link = EditText(this).apply {
            hint = "ألصق رابط منشور X هنا"
            setSingleLine(true)
            textSize = 16f
            setPadding(14, 12, 14, 12)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(link)
        extract = Button(this).apply {
            text = "استخراج الفيديو"
            isAllCaps = false
            setOnClickListener { extractVideo() }
        }
        root.addView(extract)
        root.addView(Button(this).apply {
            text = "تشغيل الأيقونة العائمة"
            isAllCaps = false
            setOnClickListener { enableFloating() }
        })
        root.addView(Button(this).apply {
            text = "فتح التنزيلات"
            isAllCaps = false
            setOnClickListener {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: Exception) { show("افتح الملفات ثم Movies/XVideoDownloader") }
            }
        })
        status = TextView(this).apply {
            text = "ألصق رابط منشور عام يحتوي على فيديو."
            textSize = 14f
            setTextColor(0xFF334155.toInt())
            setPadding(4, 24, 4, 12)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "ملاحظة: المنشورات الخاصة أو المحذوفة قد لا تعمل."
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
        })
        setContentView(ScrollView(this).apply { addView(root) })
        receiveShare(intent)
        // Start the floating bubble automatically; Android will ask for overlay permission once.
        enableFloating()
    }

    private fun show(message: String) { status.text = message }

    private fun enableFloating() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            Toast.makeText(this, "اسمح بالظهور فوق التطبيقات ثم ارجع", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else startFloating()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            startFloating()
        }
    }

    private fun startFloating() {
        try {
            startService(Intent(this, FloatingService::class.java))
            show("الأيقونة العائمة تعمل الآن.")
        } catch (_: Exception) { show("تعذر تشغيل الأيقونة العائمة. تحقق من الأذونات.") }
    }

    private fun statusId(value: String): String? = try {
        val uri = Uri.parse(value.trim())
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com"))) null
        else Regex("/status/(\\d+)").find(uri.path.orEmpty())?.groupValues?.get(1)
    } catch (_: Exception) { null }

    private fun extractVideo() {
        val id = statusId(link.text.toString())
        if (id == null) { show("الرابط غير صحيح. استخدم رابط x.com أو twitter.com."); return }
        extract.isEnabled = false
        extract.text = "جارٍ البحث..."
        show("نتصل بمصدر بيانات المنشور...")
        thread {
            try {
                val c = URL("https://api.fxtwitter.com/2/status/$id").openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "XVideoDownloader/1.1 Android")
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                c.disconnect()
                if (code !in 200..299) throw Exception("تعذر الوصول إلى المنشور (HTTP $code).")
                val json = JSONObject(body)
                if (json.optInt("code", code) != 200) throw Exception("تعذر قراءة المنشور. تأكد أنه عام.")
                val videos = json.optJSONObject("status")?.optJSONObject("media")?.optJSONArray("videos")
                val formats = mutableListOf<Format>()
                if (videos != null) for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val items = v.optJSONArray("formats") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        val u = item.optString("url", "")
                        val h = try { Uri.parse(u).host.orEmpty().lowercase() } catch (_: Exception) { "" }
                        val container = item.optString("container", "").lowercase()
                        if (u.startsWith("https://") &&
                            (container == "mp4" || u.substringBefore("?").endsWith(".mp4", true)) &&
                            (h == "video.twimg.com" || h.endsWith(".video.twimg.com"))) {
                            formats.add(Format(u, item.optInt("height", v.optInt("height")), item.optLong("bitrate")))
                        }
                    }
                }
                val unique = formats.distinctBy { it.url }.sortedWith(compareByDescending<Format> { it.height }.thenByDescending { it.bitrate })
                runOnUiThread {
                    extract.isEnabled = true
                    extract.text = "استخراج الفيديو"
                    if (unique.isEmpty()) show("لم نعثر على فيديو MP4 في هذا المنشور.")
                    else chooseFormat(id, unique)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    extract.isEnabled = true
                    extract.text = "استخراج الفيديو"
                    show(e.message ?: "حدث خطأ أثناء استخراج الفيديو.")
                }
            }
        }
    }

    private fun chooseFormat(id: String, formats: List<Format>) {
        val labels = formats.map { f -> if (f.height > 0) f.height.toString() + "p" else "جودة متاحة" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("اختر جودة الفيديو")
            .setItems(labels) { _, index -> download(formats[index], id) }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun download(f: Format, id: String) {
        try {
            val uri = Uri.parse(f.url)
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || !(host == "video.twimg.com" || host.endsWith(".video.twimg.com"))) {
                show("رابط الفيديو غير موثوق."); return
            }
            val name = "x_" + id + "_" + f.height + "p.mp4"
            show("جارٍ الاتصال بخادم الفيديو...")
            VideoFileDownloader.download(
                this,
                f.url,
                name,
                "https://x.com/i/status/$id"
            ) { success, message ->
                runOnUiThread { show(message) }
            }
        } catch (_: Exception) { show("تعذر بدء التنزيل.") }
    }

    private fun receiveShare(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND) {
            val value = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (value.contains("x.com/") || value.contains("twitter.com/")) link.setText(value)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }
}
