package com.light.applocator

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply { setPadding(0, 0, 0, 12) }
        root.addView(statusView)

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
        UpdateChecker.check(packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
        ) { latest, url ->
            // 不用对话框，顶部状态行提示 + 点击下载（页面内交互）
            updateAvailable = true
            updateText = "⬆ 新版本 v$latest 可用，点此更新"
            statusView.text = updateText
            statusView.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                } catch (_: Exception) {}
            }
        }
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

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
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
        svc.requestLocate(app.label)
        Toast.makeText(this, "正在定位「${app.label}」…", Toast.LENGTH_SHORT).show()
        // 回桌面
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
    }
}
