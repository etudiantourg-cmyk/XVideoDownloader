package com.example.xvideodownloader

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class FloatingService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private val downloads = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

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
            setOnClickListener { showPanel() }
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(58, 58, type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 18; y = 220
        }
        button.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=params.x; oy=params.y; moved=false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt()
                        if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>8) moved=true
                        params.x=ox-dx; params.y=oy+dy; wm.updateViewLayout(button,params); return true
                    }
                    MotionEvent.ACTION_UP -> { if (!moved) showPanel(); return true }
                }
                return false
            }
        })
        bubble = button
        wm.addView(button, params)
    }

    private fun showPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=28f; setStroke(2,0xFFE5E7EB.toInt()) }
        }
        val title = TextView(this).apply { text="تنزيل فيديو من X"; textSize=20f; setTextColor(0xFF111827.toInt()) }
        val input = EditText(this).apply {
            hint="ألصق رابط المنشور هنا"
            setSingleLine(true)
            val cm=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            if (cm.hasPrimaryClip()) {
                val clip=cm.primaryClip
                val value=clip?.getItemAt(0)?.coerceToText(this@FloatingService)?.toString().orEmpty()
                if (value.contains("x.com/") || value.contains("twitter.com/")) setText(value)
            }
        }
        val message = TextView(this).apply { text="يدعم المنشورات العامة التي تحتوي على فيديو."; setPadding(0,8,0,8) }
        val download = Button(this).apply { text="بدء التنزيل" }
        val close = Button(this).apply { text="إغلاق" }
        box.addView(title); box.addView(input); box.addView(message); box.addView(download); box.addView(close)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p=WindowManager.LayoutParams((resources.displayMetrics.widthPixels*0.88).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply { gravity=Gravity.CENTER }
        val panel=android.widget.PopupWindow(box,p.width,WindowManager.LayoutParams.WRAP_CONTENT,true)
        download.setOnClickListener {
            val link=input.text.toString().trim()
            if (!(link.contains("x.com/") || link.contains("twitter.com/"))) { message.text="الرابط غير صالح. أدخل رابط منشور من X."; return@setOnClickListener }
            message.text="سيتم تجهيز محرك استخراج الفيديو في الخطوة التالية. لا يمكن تنزيل رابط المنشور مباشرة كملف فيديو."
        }
        close.setOnClickListener { panel.dismiss() }
        panel.showAtLocation(bubble, Gravity.CENTER, 0, 0)
    }

    override fun onDestroy() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble=null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
