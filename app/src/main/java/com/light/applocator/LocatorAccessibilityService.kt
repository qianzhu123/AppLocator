package com.light.applocator

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class LocatorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppLocator"
        @Volatile var instance: LocatorAccessibilityService? = null

        // 已知桌面包名；同时前台包名含 "launcher" 也按桌面处理（覆盖定制 ROM）
        private val LAUNCHER_PKGS = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.mi.home",
            "com.android.launcher",
            "com.android.launcher3",
            "com.huawei.android.launcher",
            "com.oplus.home",
            "com.samsung.android.launcher",
            "com.smart.launcher",
            "com.tencent.launcher",
            "org.lineageos.launcher"
        )
        fun isLauncherPkg(pkg: String): Boolean =
            pkg in LAUNCHER_PKGS || pkg.contains("launcher") || pkg == "com.miui.home"
        const val HIGHLIGHT_DURATION_MS = 4000L
        const val MAX_PAGE_ATTEMPTS = 12
        const val MAX_BOOT_WAIT_MS = 8000L
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- 定位任务状态 ----
    private var pendingTarget: String? = null
    private var scanAttempt = 0
    private var bootWaitMs = 0L
    private var inFolder = false
    private val triedFolders = mutableSetOf<String>()
    private val seenPages = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 事件仅作日志参考；扫描由主动轮询驱动，不依赖事件（MIUI 会节流事件）
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "event pkg=${event?.packageName} type=${event?.eventType}")
        }
    }

    /** 供 MainActivity 调用：请求定位某应用（按桌面图标 label 匹配） */
    fun requestLocate(label: String) {
        Log.i(TAG, "requestLocate: $label")
        pendingTarget = label
        scanAttempt = 0
        bootWaitMs = 0
        inFolder = false
        triedFolders.clear()
        seenPages.clear()
        handler.removeCallbacks(scanTick)
        // 主动轮询扫描：等回桌面后自动开始，不依赖无障碍事件
        handler.postDelayed(scanTick, 600)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTick)
        pendingTarget = null
    }

    private val scanTick = object : Runnable {
        override fun run() {
            val target = pendingTarget ?: return
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""

            // 还没回到桌面：继续等（总等待上限 MAX_BOOT_WAIT_MS）
            if (root == null || !isLauncherPkg(pkg)) {
                bootWaitMs += 400
                if (bootWaitMs > MAX_BOOT_WAIT_MS) {
                    Log.w(TAG, "launcher never became active (last pkg=$pkg), giving up")
                    toast("未检测到桌面（当前前台：$pkg）")
                    stopScan()
                    return
                }
                handler.postDelayed(this, 400)
                return
            }

            // 1. 当前视图树找图标（桌面页、文件夹内、hotseat 均覆盖）
            val node = findIconNode(root, target)
            if (node != null) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                Log.i(TAG, "found '$target' at $rect inFolder=$inFolder")
                highlight(rect)
                toast(if (inFolder) "已定位（文件夹内）" else "已定位")
                stopScan()
                return
            }

            // 2. 在文件夹里没找到：关闭文件夹，继续找下一个
            if (inFolder) {
                Log.d(TAG, "folder miss, going back")
                performGlobalAction(GLOBAL_ACTION_BACK)
                inFolder = false
                scanAttempt++
                handler.postDelayed(this, 700)
                return
            }

            // 3. 翻页：页面签名循环一圈即认为所有页扫完 → 转入文件夹阶段
            val sig = pageSignature(root)
            if (sig !in seenPages && scanAttempt < MAX_PAGE_ATTEMPTS) {
                seenPages.add(sig)
                Log.d(TAG, "page #$scanAttempt not found, flipping")
                swipeToNextPage()
                scanAttempt++
                handler.postDelayed(this, 800)
                return
            }

            // 4. 文件夹阶段：逐个打开查找
            val folder = findFolderNode(root, triedFolders)
            if (folder != null) {
                triedFolders.add(folder.toString())
                val r = Rect().also { folder.getBoundsInScreen(it) }
                Log.d(TAG, "opening folder #${triedFolders.size}")
                clickCenter(r)
                inFolder = true
                handler.postDelayed(this, 900)
                return
            }

            // 5. 全部找不到
            Log.w(TAG, "'$target' not found on home screen")
            toast("未在桌面/文件夹找到「$target」（可能仅在应用抽屉里）")
            stopScan()
        }
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    /** 规范化文本：去空格+小写，用于图标匹配 */
    private fun normalize(s: String) = s.replace(" ", "").replace(" ", "").lowercase()

    private fun findIconNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val want = normalize(label)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable && normalize(desc) == want) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** 找未试过的文件夹图标：Pixel 形如 "名称，N个项目"；MIUI 形如 "文件夹：xxx" */
    private fun findFolderNode(root: AccessibilityNodeInfo, tried: Set<String>): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString() ?: ""
            val isFolder = n.isClickable && (desc.startsWith("文件夹") || desc.contains("个项目"))
            if (isFolder && n.toString() !in tried) return n
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
        val m = resources.displayMetrics
        val y = m.heightPixels / 2
        val path = android.graphics.Path().apply {
            moveTo(m.widthPixels * 0.85f, y.toFloat())
            lineTo(m.widthPixels * 0.15f, y.toFloat())
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
                .build(), null, null)
    }

    /** 点击矩形中心（用于打开文件夹） */
    private fun clickCenter(rect: Rect) {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val path = android.graphics.Path().apply {
            moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(), null, null)
    }

    /** 在目标位置画红色脉冲高亮（TYPE_ACCESSIBILITY_OVERLAY 必须由无障碍服务自身添加） */
    private fun highlight(rect: Rect) {
        handler.post {
            try {
                removeHighlight()
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                highlightView = HighlightView(this, rect)
                wm.addView(highlightView, params)
                highlightWm = wm
                handler.postDelayed({ removeHighlight() }, HIGHLIGHT_DURATION_MS)
            } catch (e: Exception) {
                Log.e(TAG, "highlight failed", e)
            }
        }
    }

    private var highlightView: View? = null
    private var highlightWm: WindowManager? = null

    private fun removeHighlight() {
        highlightView?.let { v -> try { highlightWm?.removeView(v) } catch (_: Exception) {} }
        highlightView = null
    }

    /** 红色圆角框 + 扩散脉冲圆环，标记目标图标 */
    private class HighlightView(
        context: android.content.Context,
        private val rect: Rect
    ) : View(context) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            color = android.graphics.Color.rgb(255, 64, 64)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
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
            val ring = Paint(stroke).apply { alpha = (255 * (1 - phase)).toInt() }
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
