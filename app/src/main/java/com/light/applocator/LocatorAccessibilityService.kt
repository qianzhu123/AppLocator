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
    private var folderScrolls = 0                     // 文件夹内已向下滚动次数

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
        folderScrolls = 0
        treeDumped = false
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

            // 翻页后比较图标签名，无变化则累计（到边判定）；换了页清空已试键
            if (checkPageChange.isNotEmpty()) {
                if (root != null && isLauncherPkg(pkg)) {
                    val now = iconSig(root)
                    if (now != checkPageChange) {
                        noChange = 0
                        triedCandidateKeys.clear()
                    } else {
                        noChange++
                    }
                }
                checkPageChange = ""
            }

            if (isLauncherPkg(pkg)) dumpTreeOnce(root)

            // 等回桌面
            if (root == null || !isLauncherPkg(pkg)) {
                // MIUI 负一屏：向右扫过头（扫完最后一页回卷）→ 全部页面已覆盖
                if (pkg.contains("personalassistant") || pkg.contains("negative")) {
                    if (leftDone) {
                        Log.w(TAG, "overshot into -1 screen; all pages covered")
                        toast("未在桌面/文件夹找到「$target」")
                        stopScan(); return
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed(this, 500); return
                }
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

            // 2. 文件夹展开态没找到 → 向下滚动一屏再找（最多3次），滚完才关闭继续
            if (folderOpen) {
                if (folderScrolls < 3) {
                    folderScrolls++
                    Log.d(TAG, "folder miss, scrolling down ($folderScrolls/3)")
                    swipeUpInFolder()
                    handler.postDelayed(this, 800); return
                }
                Log.d(TAG, "folder exhausted, closing")
                folderOpen = false
                folderScrolls = 0
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed(this, 500); return
            }

            // 3. 上一次点击打开了文件夹？（必须先判定，否则下一次点击落进已打开文件夹的内容区）
            val sig = iconSig(root)
            if (preClickSig.isNotEmpty()) {
                if (sig != preClickSig) {
                    Log.d(TAG, "candidate opened a folder")
                    folderOpen = true
                    folderScrolls = 0
                    preClickSig = ""
                    handler.postDelayed(this, 300); return
                }
                preClickSig = ""
            }

            // 4. 本页还有文件夹候选 → 逐个打开
            if (folderCandidates.isNotEmpty()) {
                val cand = folderCandidates.removeAt(0)
                val key = cand.toShortString()
                triedCandidateKeys.add(key)
                preClickSig = iconSig(root)
                Log.d(TAG, "opening candidate $key (${folderCandidates.size} left)")
                clickFolderLabel(cand)
                handler.postDelayed(this, FOLDER_SETTLE_MS); return
            }

            // 5. 收集本页新的文件夹候选
            folderCandidates = collectFolderCandidates(root)
            if (folderCandidates.isNotEmpty()) {
                Log.d(TAG, "page has ${folderCandidates.size} folder candidates")
                handler.postDelayed(this, 200); return
            }

            // 6. 翻页（之字：先左后右）；翻页成功后换页清空已试键（不同页同网格 bounds 相同）
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

    /**
     * 收集本页文件夹候选。
     * v1.8 改为 className 结构识别：MIUI 桌面文件夹图标的类名含 "Folder"
     * （如 com.miui.home.launcher.mixedfolder.MixedFolderIcon），
     * 应用图标是 BubbleTextView 等——按类名识别不再依赖 desc 文本匹配。
     * 找不到类名线索时回退旧排除法（desc 不在应用名集合），但仅在
     * desc 同时以"已删除/卸载"等场景可信时才点——默认只记录不点击。
     */
    private fun collectFolderCandidates(root: AccessibilityNodeInfo): MutableList<Rect> {
        val m = resources.displayMetrics
        val out = mutableListOf<Rect>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val cls = n.className?.toString() ?: ""
            val desc = n.contentDescription?.toString()
            if (n.isClickable && !desc.isNullOrEmpty()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                // 真实文件夹图标：MIUI 用 RelativeLayout（类名不含 Folder）；
                // 相邻页预载切片是 6px 宽（如 Rect(1194,269 - 1200,572)），宽度过滤
                val folderByStructure = (cls.endsWith("RelativeLayout") || cls.contains("Folder")) &&
                        r.width() >= 120 && onScreen(r, m.widthPixels, m.heightPixels)
                if (folderByStructure) {
                    val candKey = r.toShortString()
                    if (candKey !in triedCandidateKeys) out.add(r)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        // 先上后下、先左后右（树遍历顺序混着预载切片，视觉顺序才符合直觉）
        out.sortWith(compareBy({ it.top }, { it.left }))
        return out
    }

    /** 调试快照：把本页所有 clickable 节点的 className+desc 记到日志（一次） */
    private fun dumpTreeOnce(root: AccessibilityNodeInfo) {
        if (treeDumped) return
        treeDumped = true
        val m = resources.displayMetrics
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        val lines = mutableListOf<String>()
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            if (n.isClickable) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (onScreen(r, m.widthPixels, m.heightPixels)) {
                    lines.add("${n.className} desc=${n.contentDescription} $r")
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        Log.i(TAG, "TREE DUMP (${lines.size} clickable on page):")
        lines.forEach { Log.i(TAG, "  $it") }
    }

    private var treeDumped = false

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
                val cls = n.className?.toString() ?: ""
                // 应用图标是 TextView/ImageView；跳过文件夹的 RelativeLayout
                if (cls.endsWith("TextView") || cls.endsWith("ImageView")) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (onScreen(r, m.widthPixels, m.heightPixels) && r.width() > 120) return n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** 文件夹内向上滑（内容上移，露出下方图标） */
    private fun swipeUpInFolder() {
        val m = resources.displayMetrics
        val cx = (m.widthPixels * 0.5f)
        val path = android.graphics.Path().apply {
            moveTo(cx, m.heightPixels * 0.70f)
            lineTo(cx, m.heightPixels * 0.35f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build(), null, null)
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

    /** 点文件夹图标：大文件夹（2×2，宽>300px）点名称区（底部 15%），
     *  点中心会命中中间的应用直接启动；普通小文件夹点中心即可 */
    private fun clickFolderLabel(rect: Rect) {
        val cy = if (rect.width() > 300) rect.bottom - rect.height() * 0.08f
                 else rect.exactCenterY()
        val cx = rect.exactCenterX()
        val path = android.graphics.Path().apply {
            moveTo(cx, cy); lineTo(cx + 0.1f, cy + 0.1f)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
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
