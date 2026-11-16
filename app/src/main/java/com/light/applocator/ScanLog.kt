package com.light.applocator

import java.io.File

/** 应用内扫描日志：落盘 files/scan.log，「我的」页可查看 */
object ScanLog {
    @Volatile var dir: File? = null

    fun log(msg: String) {
        val d = dir ?: return
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            val f = File(d, "scan.log")
            f.appendText("$ts $msg\n")
            // 超过 256KB 截断重开
            if (f.length() > 256 * 1024) {
                val keep = f.readText().takeLast(128 * 1024)
                f.writeText(keep)
            }
        } catch (_: Exception) {}
    }

    fun file(): File? = dir?.let { File(it, "scan.log") }

    fun readTail(lines: Int = 200): String {
        val f = file() ?: return "（日志不可用）"
        return try {
            if (!f.exists()) "（暂无日志）"
            else f.readText().split("\n").takeLast(lines).joinToString("\n")
        } catch (e: Exception) { "（读取失败：${e.message}）" }
    }

    fun clear() {
        try { file()?.delete() } catch (_: Exception) {}
    }
}
