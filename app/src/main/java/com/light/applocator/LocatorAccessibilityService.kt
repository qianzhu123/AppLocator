package com.light.applocator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class LocatorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LocatorAccessibilityService? = null
        const val LAUNCHER_PKGS = "com.google.android.apps.nexuslauncher,com.miui.home,com.mi.android.globallauncher"
        const val HIGHLIGHT_DURATION_MS = 4000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTarget: String? = null   // 待定位的应用名(content-desc)
    private var scanAttempt = 0
    private val maxScanAttempts = 20
    private var lastPageSignature: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** 供 MainActivity 调用：请求定位某应用（按桌面图标 label 匹配） */
    fun requestLocate(label: String) {
        pendingTarget = label
        scanAttempt = 0
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val target = pendingTarget ?: return
        // 只关心桌面相关事件
        val pkg = event.packageName?.toString() ?: return
        if (!LAUNCHER_PKGS.contains(pkg)) return

        // 事件到达时桌面内容可能未稳定，稍作延迟后扫描
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, 350)
    }

    private val scanRunnable = Runnable { scanForTarget() }

    /** 扫描当前屏幕，找目标应用；没找到则翻页/开文件夹继续找 */
    private fun scanForTarget() {
        val target = pendingTarget ?: return
        val root = rootInActiveWindow ?: return
        if (!LAUNCHER_PKGS.contains(root.packageName?.toString() ?: "")) return

        // 1. 当前页找图标
        val node = findIconNode(root, target)
        if (node != null) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            highlight(rect)
            pendingTarget = null
            scanAttempt = 0
            // 若是文件夹内部找到的，先关闭文件夹？不，保留即可，高亮即可
            return
        }

        // 2. 翻页阶段：记录页面签名，翻回同一页说明所有桌面页都扫完了 → 转入文件夹阶段
        val sig = pageSignature(root)
        if (scanAttempt < maxScanAttempts && sig != lastPageSignature) {
            lastPageSignature = sig
            swipeToNextPage()
            scanAttempt++
            return
        }

        // 3. 文件夹阶段：找文件夹图标，逐个打开查找
        if (folderIndex < triedFolders.size || folderIndex == 0) {
            val folder = findFolderNode(root, triedFolders)
            if (folder != null) {
                triedFolders.add(folder.viewIdResourceName ?: folder.toString())
                val rect = Rect().also { folder.getBoundsInScreen(it) }
                folderCenter = Pair((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2)
                clickCenter(rect)
                scanAttempt = 0
                inFolder = true
                return
            }
        }

        // 4. 全部找不到，放弃
        pendingTarget = null
        scanAttempt = 0
        folderIndex = 0
        triedFolders.clear()
        inFolder = false
    }

    private var folderIndex = 0
    private val triedFolders = mutableSetOf<String>()
    private var inFolder = false
    private var folderCenter: Pair<Int, Int>? = null

    private fun findIconNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        // 桌面图标 = clickable TextView 且 content-desc == label（Pixel/MIUI 桌面均如此）
        val target = label.lowercase()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var best: AccessibilityNodeInfo? = null
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && desc.equals(target, ignoreCase = true)
                && n.isClickable) {
                best = n
                break
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }
        return best
    }

    private fun findFolderNode(root: AccessibilityNodeInfo, tried: Set<String>): AccessibilityNodeInfo? {
        // Pixel 文件夹图标 content-desc 形如 "文件夹名，x 个项目"，MIUI 形如 "文件夹：xxx"
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString() ?: ""
            val isFolder = (desc.contains("个项目") || desc.startsWith("文件夹")) && n.isClickable
            if (isFolder && n.toString() !in tried) {
                return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    private fun pageSignature(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        collectDescs(root, sb)
        return sb.toString()
    }

    private fun collectDescs(n: AccessibilityNodeInfo, sb: StringBuilder) {
        n.contentDescription?.let { sb.append(it).append('|') }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectDescs(it, sb) }
    }

    private fun swipeToNextPage() {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels / 2
        val x1 = (metrics.widthPixels * 0.85f).toInt()
        val x2 = (metrics.widthPixels * 0.15f).toInt()
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y.toFloat())
            lineTo(x2.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** 点击矩形中心（用于打开文件夹） */
    private fun clickCenter(rect: Rect) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** 在目标位置画一个高亮圆圈覆盖层（TYPE_ACCESSIBILITY_OVERLAY 必须由无障碍服务自身添加） */
    private fun highlight(rect: Rect) {
        handler.post {
            try {
                removeHighlight()
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                highlightView = HighlightView(this, rect)
                wm.addView(highlightView, params)
                highlightWm = wm
                handler.postDelayed({ removeHighlight() }, HIGHLIGHT_DURATION_MS)
            } catch (e: Exception) {
                // 覆盖层失败不影响主流程
            }
        }
    }

    private var highlightView: android.view.View? = null
    private var highlightWm: android.view.WindowManager? = null

    private fun removeHighlight() {
        highlightView?.let { v ->
            try { highlightWm?.removeView(v) } catch (_: Exception) {}
        }
        highlightView = null
    }

    /** 脉冲圆圈 + 圆角方框标记 */
    private class HighlightView(
        context: android.content.Context,
        private val rect: Rect
    ) : android.view.View(context) {
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
            color = android.graphics.Color.rgb(255, 64, 64)
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            color = android.graphics.Color.argb(60, 255, 64, 64)
        }
        private var phase = 0f
        private val ticker = object : Runnable {
            override fun run() {
                phase = if (phase > 1f) 0f else phase + 0.08f
                invalidate()
                postDelayed(this, 33)
            }
        }
        init { post(ticker) }
        override fun onDraw(canvas: Canvas) {
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f
            val r = maxOf(rect.width(), rect.height()) / 2f + 12f
            val expand = phase * 30f
            val ring = android.graphics.Paint(stroke).apply {
                alpha = (255 * (1 - phase)).toInt()
            }
            canvas.drawCircle(cx, cy, r + expand, ring)
            canvas.drawRoundRect(
                rect.left - 8f, rect.top - 8f, rect.right + 8f, rect.bottom + 8f,
                24f, 24f, fill)
            canvas.drawRoundRect(
                rect.left - 8f, rect.top - 8f, rect.right + 8f, rect.bottom + 8f,
                24f, 24f, stroke)
        }
    }
}
