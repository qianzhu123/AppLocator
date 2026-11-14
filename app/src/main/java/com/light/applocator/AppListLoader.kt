package com.light.applocator

import android.content.Context
import android.content.Intent

object AppListLoader {

    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val ris = pm.queryIntentActivities(intent, 0)
        val byPkg = LinkedHashMap<String, AppInfo>()
        for (ri in ris) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) continue
            if (byPkg.containsKey(pkg)) continue
            val ai = ri.activityInfo.applicationInfo
            val label = try { ri.loadLabel(pm).toString() } catch (e: Exception) { pkg }
            val version = try {
                pm.getPackageInfo(pkg, 0).versionName ?: "?"
            } catch (e: Exception) { "?" }
            byPkg[pkg] = AppInfo(
                label = label,
                packageName = pkg,
                versionName = version,
                installedAt = ai.sourceDir?.let {
                    java.io.File(it).lastModified()
                } ?: 0L,
                isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                apkSize = ai.sourceDir?.let { java.io.File(it).length() } ?: 0L
            )
        }
        return byPkg.values.sortedBy { it.label.lowercase() }
    }
}
