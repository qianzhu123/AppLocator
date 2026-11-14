package com.light.applocator

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 检查 GitHub 最新 Release 版本；有新版则回调（主线程） */
object UpdateChecker {

    private const val LATEST_URL =
        "https://api.github.com/repos/qianzhu123/AppLocator/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/qianzhu123/AppLocator/releases/latest"

    fun check(currentVersionName: String, onUpdate: (latest: String, url: String) -> Unit) {
        Thread {
            try {
                val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "AppLocator")
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").removePrefix("v")
                val apkUrl = json.optJSONArray("assets")
                    ?.let { arr ->
                        (0 until arr.length())
                            .asSequence()
                            .map { arr.optJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk") }
                            ?.optString("browser_download_url")
                    }
                val url = apkUrl ?: RELEASES_PAGE
                if (tag.isNotEmpty() && isNewer(tag, currentVersionName)) {
                    Handler(Looper.getMainLooper()).post { onUpdate(tag, url) }
                }
            } catch (_: Exception) {
                // 网络不可达时静默跳过
            }
        }.start()
    }

    /** 简单语义比较：1.2 > 1.1 */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }
}
