package com.example.xvideodownloader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFFF6F7FB.toInt())
        }
        val title = TextView(this).apply {
            text = "X Video Downloader"
            textSize = 26f
            setTextColor(0xFF172033.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "حمّل الفيديوهات العامة من X بسهولة"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 28)
        }
        val launch = Button(this).apply {
            text = "تفعيل الزر العائم"
            setOnClickListener { enableFloating() }
        }
        status = TextView(this).apply {
            text = "فعّل الظهور فوق التطبيقات لاستخدام الزر العائم."
            textSize = 14f
            setPadding(4, 20, 4, 12)
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(launch)
        root.addView(status)
        setContentView(root)
    }

    private fun enableFloating() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            status.text = "اسمح للتطبيق بالظهور فوق التطبيقات، ثم ارجع واضغط الزر."
            return
        }
        startService(Intent(this, FloatingService::class.java))
        status.text = "الزر العائم يعمل. يمكنك فتح X والضغط عليه."
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && Settings.canDrawOverlays(this)) {
            status.text = "الصلاحية مفعّلة. اضغط «تفعيل الزر العائم» لبدء الخدمة."
        }
    }
}
