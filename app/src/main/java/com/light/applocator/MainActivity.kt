package com.light.applocator

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.File
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var allApps: List<AppInfo>
    private lateinit var adapter: AppAdapter
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply { setPadding(0, 0, 0, 12) }
        root.addView(statusView)

        // 一键建库：从第一页全量扫描桌面，记忆所有应用/文件夹位置
        val scanBtn = android.widget.Button(this).apply { text = "扫描桌面建库（更新位置记忆）" }
        scanBtn.setOnClickListener {
            val svc = LocatorAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "无障碍服务未连接，请先开启", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                Toast.makeText(this, "开始扫描桌面…请勿操作手机", Toast.LENGTH_SHORT).show()
                svc.requestScan()
                // 回桌面
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(home)
            }
        }
        root.addView(scanBtn)

        val search = EditText(this).apply { hint = "搜索应用名或包名" }
        root.addView(search)

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(recycler)
        setContentView(root)

        allApps = AppListLoader.load(this)
        adapter = AppAdapter(allApps) { app -> onLocateClicked(app) }
        recycler.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                adapter.submit(if (q.isEmpty()) allApps else allApps.filter {
                    it.label.contains(q, true) || it.packageName.contains(q, true)
                })
            }
        })

        updateStatus()
        checkUpdate()
    }

    private fun checkUpdate() {
        UpdateChecker.check(this,
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        ) { info ->
            updateAvailable = true
            if (info.lan) {
                // 电脑端同 WiFi：对话框确认 → 直接下载 → 弹系统安装器
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本 v${info.versionName}")
                    .setMessage("检测到电脑端有更新包（${"%.1f".format(info.apkSize / 1048576.0)} MB），下载后直接安装。")
                    .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(info) }
                    .setNegativeButton("暂不", null)
                    .show()
            } else {
                // GitHub 兜底：顶部状态行提示 + 点击跳浏览器
                updateText = "⬆ 新版本 v${info.versionName} 可用，点此更新"
                statusView.text = updateText
                statusView.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.apkUrl)))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        Toast.makeText(this, "正在下载更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = UpdateChecker.download(info, File(filesDir, "update.apk"))
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

    private var updateAvailable = false
    private var updateText: String? = null

    override fun onResume() {
        super.onResume()
        if (updateAvailable && updateText != null) {
            statusView.text = updateText
        } else {
            updateStatus()
        }
    }

    private fun updateStatus() {
        val enabled = isAccessibilityEnabled()
        statusView.text = if (enabled) {
            "无障碍服务已开启 · 共 ${allApps.size} 个应用"
        } else {
            "⚠ 无障碍服务未开启，点击此行去开启（定位功能依赖它）"
        }
        statusView.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    /** 未捕获异常落盘（files/crash.log），供下次启动时诊断——MIUI 日志缓冲常被冲掉 */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "crash.log").appendText(
                    "\n==== ${System.currentTimeMillis()} thread=${t.name} ====\n" +
                    android.util.Log.getStackTraceString(e))
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        // MIUI 上 AccessibilityManager API 返回不可靠，直接读 secure settings
        val s = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) { null }
        if (s.isNullOrEmpty()) return false
        return s.split(':').any {
            it.equals("$packageName/.LocatorAccessibilityService", true) ||
            it.equals("$packageName/com.light.applocator.LocatorAccessibilityService", true)
        }
    }

    private fun onLocateClicked(app: AppInfo) {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val svc = LocatorAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务未连接（MIUI 需在最近任务里给本应用加锁并允许自启动）",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 应用名集合 = 桌面应用 + 全部已安装应用（Application label + launcher Activity label）。
        // MIUI 上小米换机等系统应用图标 desc 可能与 Application label 不一致（用 Activity label），
        // 双通道收集防止"desc 不在集合里 → 被误判为文件夹候选 → 点开"
        val pm = packageManager
        val norm = { s: String -> s.replace(" ", "").replace(" ", "").lowercase() }
        val labels = mutableSetOf<String>()
        allApps.forEach { labels.add(norm(it.label)) }
        try {
            for (ai in pm.getInstalledApplications(0)) {
                pm.getApplicationLabel(ai)?.toString()?.let { labels.add(norm(it)) }
                // 该应用 launcher Activity 的 label 也收进来（MIUI 图标 desc 常用这个）
                val launchIntent = pm.getLaunchIntentForPackage(ai.packageName) ?: continue
                val actName = launchIntent.resolveActivity(pm) ?: continue
                val actInfo = pm.getActivityInfo(actName, 0)
                actInfo.loadLabel(pm)?.toString()?.let { labels.add(norm(it)) }
            }
        } catch (_: Exception) {}
        svc.requestLocate(app.label, labels)
        Toast.makeText(this, "正在定位「${app.label}」…", Toast.LENGTH_SHORT).show()
        // 回桌面
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }
}
