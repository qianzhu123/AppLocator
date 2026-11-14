package com.light.applocator

import android.content.Context

/**
 * 文件夹记忆：文件夹名 → 其中可见的应用 desc 清单（持久化 SharedPreferences）。
 * 用途：
 *  1. 查找前先查记忆：目标在某个文件夹里 → 直达该文件夹
 *  2. 记忆 miss 时从当前页继续扫，并把新事实写回（防应用被移动后一直找不到）
 */
object FolderMemory {
    private const val PREFS = "folder_memory"

    fun put(folderDesc: String, apps: List<String>) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("folder:$folderDesc", apps.joinToString("\u0001")).apply()
    }

    fun get(folderDesc: String): List<String> {
        val ctx = appContext ?: return emptyList()
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("folder:$folderDesc", null)
            ?.split("\u0001")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /** 反查：目标应用在哪个文件夹（返回最新写入的文件夹名） */
    fun findFolderFor(appDesc: String): String? {
        val ctx = appContext ?: return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (k.startsWith("folder:") && v.toString().split("\u0001").any { it == appDesc }) {
                return k.removePrefix("folder:")
            }
        }
        return null
    }

    fun clear() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Volatile var appContext: Context? = null
}
