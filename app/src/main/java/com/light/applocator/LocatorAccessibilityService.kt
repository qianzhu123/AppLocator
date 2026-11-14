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

/**
 * 定位引擎 v1.4：
 *  - 之字翻页：先向左扫到尽头，再向右扫回起点方向，覆盖全部页面（与起始页无关）
 *  - 文件夹探测：候选 = desc 不在已装应用名集合中的 clickable 图标；
 *    点开后视图树变化 → 判定为文件夹并扫描内部；无变化 → 下一个候选
 *  - 高亮：独立小窗口定位在节点坐标处，避免整屏窗口的坐标系偏差
 */
class LocatorAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppLocator"
        @Volatile var instance: LocatorAccessibilityService? = null

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
            pkg in LAUNCHER_PKGS || pkg.contains("launcher")

        const val HIGHLIGHT_DURATION_MS = 4000L
        const val MAX_STEPS = 80
        const val MAX_BOOT_WAIT_MS = 8000L
        const val EDGE_THRESHOLD = 2          // 连续2次翻页无变化 → 到边
        const val SWIPE_SETTLE_MS = 900L      // 翻页动画等待
        const val FOLDER_SETTLE_MS = 1100L    // 文件夹打开动画等待

        fun onScreen(r: Rect, w: Int, h: Int): Boolean {
            val ix = maxOf(0, minOf(r.right, w)) - maxOf(0, r.left)
            val iy = maxOf(0, minOf(r.bottom, h)) - maxOf(0, r.top)
            val area = maxOf(1, r.width() * r.height())
            return (ix * iy).toFloat() / area >= 0.6f && r.left < w && r.right > 0 && r.top < h
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- 任务状态 ----
    private var pendingTarget: String? = null
    private var appLabels: Set<String> = emptySet()   // 已装应用名（规范化），用于排除法
    private var step = 0
    private var bootWaitMs = 0L
    private var folderOpen = false                    // 当前是否处于文件夹展开态
    private var sweepDir = 0                          // 0=向左(下一页) 1=向右(上一页)
    private var leftDone = false                      // 向左阶段已完成
    private var noChange = 0                          // 连续翻页无变化计数（到边判定）
    private var lastIconSig = ""                      // 本页图标签名
    private var folderCandidates = mutableListOf<Rect>()  // 本页待探测候选
    private var triedCandidateKeys = mutableSetOf<String>()
    private var preClickSig = ""                      // 点候选前的签名

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    fun requestLocate(label: String, labels: Set<String>) {
        Log.i(TAG, "requestLocate: $label")
        pendingTarget = label
        appLabels = labels
        step = 0
        bootWaitMs = 0
        folderOpen = false
        sweepDir = 0
        leftDone = false
        noChange = 0
        lastIconSig = ""
        folderCandidates.clear()
        triedCandidateKeys.clear()
        preClickSig = ""
        handler.removeCallbacks(scanTick)
        handler.postDelayed(scanTick, 600)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTick)
        pendingTarget = null
        folderOpen = false
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private val scanTick = object : Runnable {
        override fun run() {
            val target = pendingTarget ?: return
            if (++step > MAX_STEPS) {
                Log.w(TAG, "step limit reached")
                toast("定位超时，未找到「$target」")
                stopScan(); return
            }
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""

            // 翻页后比较图标签名，无变化则累计（到边判定）
            if (checkPageChange.isNotEmpty()) {
                if (root != null && isLauncherPkg(pkg)) {
                    val now = iconSig(root)
                    noChange = if (now == checkPageChange) noChange + 1 else 0
                }
                checkPageChange = ""
            }

            // 等回桌面
            if (root == null || !isLauncherPkg(pkg)) {
                if (preClickSig.isNotEmpty()) {
                    // 探测点击误开了别的应用（非文件夹）→ BACK 撤销，继续扫描
                    Log.d(TAG, "probe launched app pkg=$pkg, backing out")
                    preClickSig = ""
                    checkPageChange = ""
                    bootWaitMs = 0
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed(this, 700); return
                }
                bootWaitMs += 400
                if (bootWaitMs > MAX_BOOT_WAIT_MS) {
                    Log.w(TAG, "launcher never active (pkg=$pkg)")
                    toast("未检测到桌面（当前前台：$pkg）")
                    stopScan(); return
                }
                handler.postDelayed(this, 400); return
            }

            // 1. 当前树找图标
            val node = findIconNode(root, target)
            if (node != null) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                Log.i(TAG, "found '$target' at $rect folderOpen=$folderOpen")
                highlight(rect)
                toast(if (folderOpen) "已定位（文件夹内）" else "已定位")
                stopScan(); return
            }

            // 2. 文件夹展开态没找到 → 关闭继续
            if (folderOpen) {
                Log.d(TAG, "folder miss, closing")
                folderOpen = false
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed(this, 500); return
            }

            // 3. 本页还有文件夹候选 → 逐个探测
            if (folderCandidates.isNotEmpty()) {
                val cand = folderCandidates.removeAt(0)
                val key = cand.toShortString()
                triedCandidateKeys.add(key)
                preClickSig = iconSig(root)
                Log.d(TAG, "probing candidate $key (${folderCandidates.size} left)")
                clickCenter(cand)
                handler.postDelayed(this, FOLDER_SETTLE_MS); return
            }

            // 3b. 上一轮点开的候选如果是文件夹 → 此时 tree 已变化，判定
            val sig = iconSig(root)
            if (preClickSig.isNotEmpty() && sig != preClickSig && !folderOpen) {
                // 视图变化 → 当作文件夹打开了（内部可能就是要找的）
                Log.d(TAG, "candidate opened a folder")
                folderOpen = true
                preClickSig = ""
                handler.postDelayed(this, 300); return
            }
            preClickSig = ""

            // 4. 收集本页新的文件夹候选（desc 非空、不在应用名集合中）
            folderCandidates = collectFolderCandidates(root)
            if (folderCandidates.isNotEmpty()) {
                Log.d(TAG, "page has ${folderCandidates.size} folder candidates")
                handler.postDelayed(this, 200); return
            }

            // 5. 翻页（之字：先左后右）
            if (!leftDone) {
                // 向左阶段：无变化达阈值 → 到左边界，转右
                if (noChange >= EDGE_THRESHOLD) {
                    leftDone = true
                    noChange = 0
                    Log.d(TAG, "left edge reached, sweeping right")
                    handler.postDelayed(this, 300); return
                }
                swipe(0)
                handler.postDelayed(this, SWIPE_SETTLE_MS)
                // 下轮先检查页面是否变化
                checkPageChange = sig
                return
            } else {
                if (noChange >= EDGE_THRESHOLD) {
                    Log.w(TAG, "'$target' not found (both edges swept)")
                    toast("未在桌面/文件夹找到「$target」（可能仅在应用抽屉里）")
                    stopScan(); return
                }
                swipe(1)
                handler.postDelayed(this, SWIPE_SETTLE_MS)
                checkPageChange = sig
                return
            }
        }

        private var checkPageChange: String = ""
    }

    private fun collectFolderCandidates(root: AccessibilityNodeInfo): MutableList<Rect> {
        val m = resources.displayMetrics
        val out = mutableListOf<Rect>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable) {
                val key = normalize(desc)
                // 排除法：desc 是已装应用名 → 是应用图标不是文件夹
                if (key.isNotEmpty() && key !in appLabels) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (onScreen(r, m.widthPixels, m.heightPixels)) {
                        val candKey = r.toShortString() + key
                        if (candKey !in triedCandidateKeys) out.add(r)
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    private fun iconSig(root: AccessibilityNodeInfo): String {
        val m = resources.displayMetrics
        val sb = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (onScreen(r, m.widthPixels, m.heightPixels)) sb.append(desc).append('|')
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return sb.toString()
    }

    private fun normalize(s: String) =
        s.replace(" ", "").replace(" ", "").lowercase()

    private fun findIconNode(root: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        val want = normalize(label)
        val m = resources.displayMetrics
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable && normalize(desc) == want) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (onScreen(r, m.widthPixels, m.heightPixels)) return n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** dir=0 向左(下一页) dir=1 向右(上一页) */
    private fun swipe(dir: Int) {
        val m = resources.displayMetrics
        val y = (m.heightPixels * 0.55f).toInt()
        val path = android.graphics.Path()
        if (dir == 0) {
            path.moveTo(m.widthPixels * 0.85f, y.toFloat())
            path.lineTo(m.widthPixels * 0.15f, y.toFloat())
        } else {
            path.moveTo(m.widthPixels * 0.15f, y.toFloat())
            path.lineTo(m.widthPixels * 0.85f, y.toFloat())
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build(), null, null)
    }

    private fun clickCenter(rect: Rect) {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val path = android.graphics.Path().apply {
            moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build(), null, null)
    }

    /** 独立小窗口高亮：窗口原点=节点坐标，避免整屏窗口坐标系偏差 */
    private fun highlight(rect: Rect) {
        handler.post {
            try {
                removeHighlight()
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val pad = 14
                val params = WindowManager.LayoutParams(
                    rect.width() + pad * 2,
                    rect.height() + pad * 2,
                    rect.left - pad,
                    rect.top - pad,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                params.gravity = Gravity.TOP or Gravity.START
                highlightView = HighlightView(this, rect.width() + pad * 2f, rect.height() + pad * 2f)
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

    /** 红色圆角框 + 扩散脉冲环，画在自己小窗口内 */
    private class HighlightView(
        context: android.content.Context,
        private val w: Float,
        private val h: Float
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
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f + 6f
            val expand = phase * 26f
            val ring = Paint(stroke).apply { alpha = (255 * (1 - phase)).toInt() }
            canvas.drawCircle(cx, cy, r + expand, ring)
            canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 24f, 24f, fill)
            canvas.drawRoundRect(4f, 4f, w - 4f, h - 4f, 24f, 24f, stroke)
        }
    }
}
