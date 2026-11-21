package com.example.xvideodownloader

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Context

/**
 * Reads visible accessibility text from X only to detect a post URL.
 * No screenshots, credentials, or screen content are transmitted.
 */
class XPostAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != "com.twitter.android") return
        val candidates = ArrayList<String>()
        event.text?.forEach { candidates.add(it?.toString().orEmpty()) }
        collectText(rootInActiveWindow, candidates, 0)
        val pattern = Regex("""https?://(?:www\.)?(?:x\.com|twitter\.com)/[^\s/?#]+/status/(\d+)""", RegexOption.IGNORE_CASE)
        for (candidate in candidates) {
            val id = pattern.find(candidate)?.groupValues?.get(1) ?: continue
            getSharedPreferences("detected_post", Context.MODE_PRIVATE).edit()
                .putString("status_id", id)
                .putString("status_url", "https://x.com/i/status/$id")
                .putLong("detected_at", System.currentTimeMillis())
                .apply()
            return
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 18 || out.size > 500) return
        try {
            node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
            for (i in 0 until node.childCount) collectText(node.getChild(i), out, depth + 1)
        } catch (_: Exception) { }
    }

    override fun onInterrupt() = Unit
}