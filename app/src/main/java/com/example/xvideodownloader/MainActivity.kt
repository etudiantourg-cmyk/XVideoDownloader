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
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, radius: Float, stroke: Int = 0): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius.toInt()).toFloat()
                if (stroke != 0) setStroke(dp(1), stroke)
            }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFEAF5FF.toInt(), 0xFFF7F4FF.toInt(), 0xFFFFF8EF.toInt())
            )
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(26), dp(20), dp(24))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1268D8.toInt(), 0xFF5636B8.toInt(), 0xFF8A42B8.toInt())
            ).apply { cornerRadius = dp(30).toFloat() }
            elevation = dp(8).toFloat()
        }
        val mascot = TextView(this).apply {
            text = "⬇"
            textSize = 48f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD75E.toInt())
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            paint.setShadowLayer(dp(5).toFloat(), 0f, dp(3).toFloat(), 0x99000000.toInt())
        }
        hero.addView(mascot, LinearLayout.LayoutParams(dp(94), dp(82)))
        hero.addView(TextView(this).apply {
            text = "X VIDEO DOWNLOADER"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(5))
        })
        hero.addView(TextView(this).apply {
            text = "حمّل فيديوهاتك المفضّلة بسهولة"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFFE7E9FF.toInt())
        })
        page.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(0xFFFFFFFF.toInt(), 24f, 0xFFE4E9F3.toInt())
            elevation = dp(4).toFloat()
        }
        section.addView(TextView(this).apply {
            text = "ابدأ التنزيل"
            textSize = 20f
            setTextColor(0xFF172554.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(5))
        })
        section.addView(TextView(this).apply {
            text = "الصق رابط منشور عام من X يحتوي على فيديو"
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            gravity = Gravity.RIGHT
            setPadding(0, 0, 0, dp(14))
        })
        link = EditText(this).apply {
            hint = "https://x.com/…/status/…"
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFF172033.toInt())
            setHintTextColor(0xFF9AA6B8.toInt())
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(0xFFF4F7FC.toInt(), 16f, 0xFFDCE5F1.toInt())
        }
        section.addView(link, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(12) })
        extract = Button(this).apply {
            text = "⬇   استخراج وتنزيل الفيديو"
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF087FCE.toInt(), 0xFF3158D8.toInt())
            ).apply { cornerRadius = dp(16).toFloat() }
            setOnClickListener { extractVideo() }
        }
        section.addView(extract, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(10) })
        val floatingButton = Button(this).apply {
            text = "●   تشغيل الأيقونة العائمة"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF2453A6.toInt())
            background = rounded(0xFFEAF2FF.toInt(), 16f)
            setOnClickListener { enableFloating() }
        }
        section.addView(floatingButton, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
        section.addView(Button(this).apply {
            text = "▣   فتح التنزيلات"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF475569.toInt())
            background = rounded(0xFFF2F5FA.toInt(), 16f)
            setOnClickListener {
                try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                catch (_: Exception) { show("افتح الملفات ثم Movies/XVideoDownloader") }
            }
        }, LinearLayout.LayoutParams(-1, dp(46)))
        page.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        status = TextView(this).apply {
            text = "جاهز! ألصق رابط فيديو من X للبدء."
            textSize = 14f
            setTextColor(0xFF23416D.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFFE7F5EF.toInt(), 18f)
        }
        page.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        page.addView(TextView(this).apply {
            text = "ملاحظة: يجب أن يكون المنشور عاماً. قد تمنع قيود X تنزيل بعض الفيديوهات."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF718096.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFF7F8FC.toInt())
            addView(page)
        })
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
