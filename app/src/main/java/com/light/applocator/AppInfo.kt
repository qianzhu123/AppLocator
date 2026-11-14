package com.light.applocator

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val installedAt: Long,
    val isSystem: Boolean,
    val apkSize: Long,
    // 定位后填充
    var location: String? = null,   // "桌面第2页" / "文件夹「工具」"
    var bounds: String? = null       // "[x1,y1][x2,y2]"
)
