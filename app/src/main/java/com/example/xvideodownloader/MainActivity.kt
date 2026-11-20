package com.example.xvideodownloader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        handleSharedLink(intent)
        requestLegacyStoragePermission()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 28)
            setBackgroundColor(0xFFF5F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "X Video Downloader"
            textSize = 27f
            setTextColor(0xFF14213D.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "نزّل الفيديوهات العامة من منشورات X"
            textSize = 16f
            setTextColor(0xFF475569.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 32)
        }
        val enable = Button(this).apply {
            text = "تشغيل الزر العائم"
            setOnClickListener { enableFloating() }
        }
        val how = TextView(this).apply {
            text = "طريقة الاستخدام\n1. شغّل الزر العائم واسمح بالظهور فوق التطبيقات.\n2. افتح X وانسخ رابط المنشور أو شاركه مع التطبيق.\n3. اضغط الدائرة العائمة ثم «بدء التنزيل».\n\nيعمل مع المنشورات العامة التي تحتوي على فيديو."
            textSize = 15f
            setTextColor(0xFF334155.toInt())
            setPadding(4, 24, 4, 12)
        }
        status = TextView(this).apply {
            text = "الزر العائم متوقف."
            textSize = 14f
            setTextColor(0xFF0F766E.toInt())
            setPadding(4, 18, 4, 12)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(enable, LinearLayout.LayoutParams(-1, -2))
        root.addView(how)
        root.addView(status)
        setContentView(root)
    }

    private fun requestLegacyStoragePermission() {
        if (Build.VERSION.SDK_INT in 23..28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
        }
    }

    private fun enableFloating() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "اسمح للتطبيق بالظهور فوق التطبيقات، ثم ارجع واضغط الزر."
            return
        }
        startService(Intent(this, FloatingService::class.java))
        status.text = "الزر العائم يعمل الآن. افتح X واضغط الدائرة."
    }

    private fun handleSharedLink(incoming: Intent?) {
        if (incoming?.action == Intent.ACTION_SEND && incoming.type?.startsWith("text/") == true) {
            val shared = incoming.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (shared.contains("x.com/") || shared.contains("twitter.com/")) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("X link", shared))
                status.text = "تم استلام رابط X ونسخه. شغّل الزر العائم لبدء التنزيل."
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && Settings.canDrawOverlays(this)) {
            status.text = "صلاحية الظهور فوق التطبيقات مفعّلة."
        }
    }
}
