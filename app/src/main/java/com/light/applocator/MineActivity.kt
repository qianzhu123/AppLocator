package com.light.applocator

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.accessibility.AccessibilityManager

/**
 * 「我的」页面：无障碍状态、MIUI 存活设置引导（省电策略/自启动）、
 * 记忆库管理与黑名单、检查更新。
 */
class MineActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var a11yCard: TextView
    private lateinit var memCard: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)

        title("我的")

        // ---- 无障碍状态卡 ----
        a11yCard = card("无障碍服务：检测中…")
        a11yCard.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                tryStartAccessibilitySettings()
            }
        }
        root.addView(a11yCard)

        // ---- MIUI 存活引导卡（点进系统对应页）----
        val survive = card("⚠ MIUI 保活设置（点此逐项设置）\n1. 省电策略 → 无限制\n2. 自启动 → 允许\n3. 无障碍服务保持开启\n\n不做这三步，MIUI 会杀进程并掐断无障碍，定位会失效。")
        survive.setOnClickListener { showSurviveGuide() }
        root.addView(survive)

        // ---- 记忆库卡 ----
        memCard = card("桌面记忆库：加载中…")
        memCard.setOnClickListener { memCardInfo = !memCardInfo; refreshMemoryCard() }
        root.addView(memCard)

        // ---- 黑名单管理 ----
        val blockCard = card("误点黑名单：查看/清空")
        blockCard.setOnClickListener { showBlacklist() }
        root.addView(blockCard)

        // ---- 检查更新 ----
        val updateCard = card("检查更新")
        updateCard.setOnClickListener {
            Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show()
            UpdateChecker.check(this,
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
            ) { info ->
                if (info.lan) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本 v${info.versionName}")
                        .setMessage("检测到电脑端有更新包（${"%.1f".format(info.apkSize / 1048576.0)} MB），下载后直接安装。")
                        .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(info) }
                        .setNegativeButton("暂不", null)
                        .show()
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.apkUrl)))
                    } catch (_: Exception) {}
                }
            }
        }
        root.addView(updateCard)

        // ---- 回到应用列表 ----
        val backCard = card("← 返回应用列表")
        backCard.setOnClickListener { finish() }
        root.addView(backCard)
    }

    private var memCardInfo = false

    override fun onResume() {
        super.onResume()
        refreshA11yCard()
        refreshMemoryCard()
    }

    private fun title(s: String) {
        val t = TextView(this).apply {
            text = s
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }
        root.addView(t)
    }

    private fun card(text: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x1418A0D8.toInt())  // 半透明主题色
                cornerRadius = 14 * dp
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (12 * dp).toInt()
            layoutParams = lp
        }
    }

    private fun refreshA11yCard() {
        val enabled = isAccessibilityEnabled()
        val bound = LocatorAccessibilityService.instance != null
        a11yCard.text = when {
            enabled && bound -> "✓ 无障碍服务已开启，定位功能可用"
            enabled -> "⚠ 无障碍已注册但服务未连接，尝试去设置里关掉再开"
            else -> "✗ 无障碍服务未开启，点此去开启（定位功能依赖它）"
        }
    }

    private fun refreshMemoryCard() {
        val (apps, folders) = FolderMemory.stats()
        memCard.text = if (apps == 0 && folders == 0) {
            "桌面记忆库：空\n点主界面「扫描桌面建库」生成位置记忆"
        } else if (memCardInfo) {
            "桌面记忆库：$apps 个应用位置，$folders 个文件夹\n（点击收起）\n" +
                FolderMemory.dump()
        } else {
            "桌面记忆库：$apps 个应用位置，$folders 个文件夹\n（点击展开明细）"
        }
    }

    private fun showSurviveGuide() {
        val items = arrayOf("① 省电策略设为「无限制」", "② 允许自启动", "③ 打开无障碍设置")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("MIUI 保活三步")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openBatteryOptimization()
                    1 -> openAutostart()
                    2 -> tryStartAccessibilitySettings()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 省电策略：跳应用详情（MIUI 上「省电策略」在应用信息页内） */
    private fun openBatteryOptimization() {
        try {
            // 优先 MIUI 专属电池设置
            val miuiIntent = Intent().apply {
                setClassName("com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                putExtra("package_name", packageName)
                putExtra("package_label", "应用定位器")
            }
            startActivity(miuiIntent)
        } catch (_: Exception) {
            try {
                // 兜底：忽略电池优化系统页
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                Toast.makeText(this, "请手动到 设置→应用→应用定位器→省电策略", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** MIUI 自启动管理 */
    private fun openAutostart() {
        try {
            val intent = Intent().apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "请手动到 设置→应用管理→权限→自启动", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryStartAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {}
    }

    private fun showBlacklist() {
        val items = FolderMemory.blockedItems()
        val msg = if (items.isEmpty()) "黑名单为空（扫描时误点的小组件会自动加入）"
        else items.joinToString("\n")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("误点黑名单")
            .setMessage(msg)
            .setPositiveButton("清空") { _, _ ->
                FolderMemory.clearBlocked()
                Toast.makeText(this, "黑名单已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (am.isEnabled) {
            val list = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_GENERIC)
            if (list.any { it.resolveInfo?.serviceInfo?.packageName == packageName }) return true
        }
        // MIUI 上 AccessibilityManager API 不可靠，再读 secure settings
        val s = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) { null }
        if (s.isNullOrEmpty()) return false
        return s.split(':').any {
            it.equals("$packageName/.LocatorAccessibilityService", true) ||
            it.equals("$packageName/com.light.applocator.LocatorAccessibilityService", true)
        }
    }

    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash.log").appendText(
                    "\n==== ${System.currentTimeMillis()} thread=${t.name} ====\n" +
                    android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = UpdateChecker.download(info, java.io.File(filesDir, "update.apk"))
            runOnUiThread {
                if (f == null) {
                    Toast.makeText(this, "更新下载失败，请确认电脑端更新服务已开启", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.updatefile", f)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "无法启动安装", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
