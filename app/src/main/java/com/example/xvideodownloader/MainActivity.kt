package com.example.xvideodownloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private val siteUrl = "https://etudiantourg-cmyk.github.io/XVideoDownloader/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidDownloader")
        setContentView(webView)
        if (intent?.action == Intent.ACTION_SEND) handleSharedLink(intent)
        webView.loadUrl(siteUrl)
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun enableFloating() {
            runOnUiThread {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    Toast.makeText(this@MainActivity, "اسمح بالظهور فوق التطبيقات ثم ارجع إلى التطبيق", Toast.LENGTH_LONG).show()
                } else {
                    startService(Intent(this@MainActivity, FloatingService::class.java))
                    Toast.makeText(this@MainActivity, "الأيقونة العائمة تعمل الآن", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun downloadVideo(videoUrl: String, fileName: String) {
            runOnUiThread {
                try {
                    val uri = Uri.parse(videoUrl)
                    val host = uri.host?.lowercase().orEmpty()
                    if (uri.scheme != "https" || !(host == "video.twimg.com" || host.endsWith(".video.twimg.com"))) {
                        Toast.makeText(this@MainActivity, "رابط الفيديو غير موثوق", Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90).ifBlank { "x_video.mp4" }
                    val request = DownloadManager.Request(uri)
                        .setTitle(safeName)
                        .setDescription("X Video Downloader")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_MOVIES, "XVideoDownloader/$safeName")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    Toast.makeText(this@MainActivity, "بدأ تنزيل الفيديو", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "تعذر بدء التنزيل", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleSharedLink(incoming: Intent?) {
        val shared = incoming?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (shared.contains("x.com/") || shared.contains("twitter.com/")) {
            getSharedPreferences("xvd", MODE_PRIVATE).edit().putString("shared_link", shared).apply()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) handleSharedLink(intent)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
