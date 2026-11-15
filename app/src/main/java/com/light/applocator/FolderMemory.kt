package com.light.applocator

import android.content.Context

/**
 * 桌面位置库（SharedPreferences 持久化）：
 *  - app@<名字>      = "p<页码>"                    应用在哪一页
 *  - folder@<名字>   = "p<页码>"                    文件夹在哪一页
 *  - fin<名字>       = 应用名\u0001应用名…           文件夹内容清单
 *  - <文件夹名>      = 内容清单（旧格式，兼容保留）
 */
object FolderMemory {
    private const val PREFS = "folder_memory"

    fun put(folderDesc: String, apps: List<String>) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("fin$folderDesc", apps.joinToString("\u0001")).apply()
    }

    fun get(folderDesc: String): List<String> {
        val ctx = appContext ?: return emptyList()
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("fin$folderDesc", null)
            ?.split("\u0001")
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /** 应用→页码 */
    fun putAppPage(appDesc: String, page: Int) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("app@$appDesc", "p$page").apply()
    }

    fun getAppPage(appDesc: String): Int? =
        getEntry("app@$appDesc")?.removePrefix("p")?.toIntOrNull()

    /** 文件夹→页码 */
    fun putFolderPage(folderDesc: String, page: Int) {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("folder@$folderDesc", "p$page").apply()
    }

    fun getFolderPage(folderDesc: String): Int? =
        getEntry("folder@$folderDesc")?.removePrefix("p")?.toIntOrNull()

    /** 反查：目标应用在哪个文件夹（按内容清单） */
    fun findFolderFor(appDesc: String): String? {
        val ctx = appContext ?: return null
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((k, v) in p.all) {
            if (k.startsWith("fin") && v.toString().split("\u0001").any { it == appDesc }) {
                return k.removePrefix("fin")
            }
        }
        return null
    }

    private fun getEntry(key: String): String? {
        val ctx = appContext ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
    }

    fun clear() {
        val ctx = appContext ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Volatile var appContext: Context? = null
}
