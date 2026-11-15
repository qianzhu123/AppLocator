package com.light.applocator

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 应用内更新，局域网优先：
 *  1. 先问同 WiFi 的电脑端小服务（tool/server.js，/api/version + /apk），
 *     有新版则直接下载 APK 弹系统安装器（不跳浏览器）
 *  2. 电脑端不在时才兜底查 GitHub Releases（只能跳浏览器下载）
 */
object UpdateChecker {

    /** 电脑端地址（IP:端口）；与 xuexitong 工具同一套路 */
    private const val PC_HOST = "192.168.43.30:8778"
    private const val LAST_HOST_KEY = "update_host"
    private const val PREFS = "applocator"

    private const val LATEST_URL =
        "https://api.github.com/repos/qianzhu123/AppLocator/releases/latest"
    private const val RELEASES_PAGE =
        "https://github.com/qianzhu123/AppLocator/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val apkSize: Long,
        val apkUrl: String,   // 完整 http URL
        val lan: Boolean,     // true=电脑端局域网直装 false=GitHub 跳浏览器
    )

    fun lastHost(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_HOST_KEY, "") ?: ""

    fun rememberHost(ctx: Context, host: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_HOST_KEY, host).apply()
    }

    /**
     * 检查更新（后台线程，回调主线程）。
     * 先试电脑端（上次成功的 host + 默认 PC IP），全部失败再走 GitHub。
     */
    fun check(ctx: Context, currentVersionName: String, onUpdate: (UpdateInfo) -> Unit) {
        Thread {
            val hosts = mutableListOf<String>()
            lastHost(ctx).takeIf { it.isNotEmpty() }?.let { hosts.add(it) }
            hosts.add(PC_HOST)
            for (h in hosts.distinct()) {
                val info = checkLan(h, currentVersionName)
                if (info != null) {
                    rememberHost(ctx, h)
                    Handler(Looper.getMainLooper()).post { onUpdate(info) }
                    return@Thread
                }
            }
            checkGithub(currentVersionName)?.let {
                Handler(Looper.getMainLooper()).post { onUpdate(it) }
            }
        }.start()
    }

    private fun checkLan(host: String, currentVersionName: String): UpdateInfo? {
        try {
            val conn = URL("http://$host/api/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val body = try {
                if (conn.responseCode != 200) { conn.disconnect(); return null }
                conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
            } catch (_: Exception) { return null }
            val json = JSONObject(body)
            if (!json.optBoolean("available", false)) return null
            val version = json.optString("versionName", "")
            if (version.isEmpty() || !isNewer(version, currentVersionName)) return null
            return UpdateInfo(
                versionName = version,
                apkSize = json.optLong("apkSize", 0L),
                apkUrl = "http://$host/apk",
                lan = true,
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun checkGithub(currentVersionName: String): UpdateInfo? {
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AppLocator")
            if (conn.responseCode != 200) { conn.disconnect(); return null }
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
            if (tag.isEmpty() || !isNewer(tag, currentVersionName)) return null
            return UpdateInfo(
                versionName = tag,
                apkSize = 0,
                apkUrl = apkUrl ?: RELEASES_PAGE,
                lan = false,
            )
        } catch (_: Exception) {
            return null
        }
    }

    /** 从电脑端下载 APK 到私有目录，返回文件供安装器使用 */
    fun download(info: UpdateInfo, into: File): File? {
        if (!info.lan) return null
        return try {
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 60000
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            conn.inputStream.use { input -> into.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            into
        } catch (_: Exception) {
            null
        }
    }

    /** 手动检查用：任一更新源可达即 true（区分"已是最新"与"检查失败"） */
    fun probeReachable(): Boolean {
        try {
            val conn = URL("http://$PC_HOST/api/version").openConnection() as HttpURLConnection
            conn.connectTimeout = 2500
            conn.readTimeout = 2500
            val ok = conn.responseCode == 200
            conn.disconnect()
            if (ok) return true
        } catch (_: Exception) {}
        try {
            val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "AppLocator")
            val ok = conn.responseCode == 200
            conn.disconnect()
            return ok
        } catch (_: Exception) {
            return false
        }
    }

    /** 简单语义比较：1.5 > 1.4 */    private fun isNewer(remote: String, current: String): Boolean {
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
