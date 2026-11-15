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
 * 定位引擎 v2.3（用户定的架构）：
 *  - 「扫描桌面建库」：从第一页全量扫一遍，记忆 app→页码、文件夹→页码+内容清单
 *  - 定位 = 查记忆直达（跳页→开文件夹→高亮）；记忆 miss → 从第一页全量重扫并更新
 *  - 文件夹识别 = 结构（RelativeLayout/含Folder + 近方形 + 宽≥120px，滤掉小组件和预载切片）
 *  - 大文件夹（2×2）点底部名称区，点中心会启动中间的应用（小米换机教训）
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
        const val MAX_STEPS = 200
        const val MAX_BOOT_WAIT_MS = 8000L
        const val EDGE_THRESHOLD = 2
        const val SWIPE_SETTLE_MS = 900L
        const val FOLDER_SETTLE_MS = 1100L
        const val MAX_FOLDER_SCROLLS = 5

        fun onScreen(r: Rect, w: Int, h: Int): Boolean {
            val ix = maxOf(0, minOf(r.right, w)) - maxOf(0, r.left)
            val iy = maxOf(0, minOf(r.bottom, h)) - maxOf(0, r.top)
            val area = maxOf(1, r.width() * r.height())
            return (ix * iy).toFloat() / area >= 0.6f && r.left < w && r.right > 0 && r.top < h
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    // ---- 模式 ----
    /** scan=建库  locate=按记忆直达+miss重建  */
    private var mode = ""
    private var pendingTarget: String? = null      // locate 模式的目标（规范化名）
    private var targetRaw: String? = null          // 原始名（toast 用）

    // ---- 扫描状态（两模式共用）----
    private var step = 0
    private var bootWaitMs = 0L
    private var folderOpen = false
    private var leftDone = false                   // false=向左翻页阶段 true=向右回扫阶段
    private var noChange = 0
    private var page = 0                           // 当前页码（向左翻 +1）
    private var folderCandidates = mutableListOf<Rect>()
    private var triedFolders = mutableSetOf<String>()   // 按文件夹名去重（跨页可靠）
    private var preClickSig = ""
    private var folderScrolls = 0
    private var lastFolderSig = ""
    private var openedFolderDesc: String? = null
    private var checkPageChange = ""               // 翻页后待比较的签名

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FolderMemory.appContext = applicationContext
        Log.i(TAG, "accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /** 一键建库：回桌面，从第一页全量扫描 */
    fun requestScan() {
        Log.i(TAG, "requestScan: build library")
        mode = "scan"
        resetState()
        goHome()
    }

    /** 定位：先查记忆；miss 时全量重扫（从第一页） */
    fun requestLocate(label: String, labels: Set<String>) {
        Log.i(TAG, "requestLocate: $label")
        mode = "locate"
        pendingTarget = normalize(label)
        targetRaw = label
        appLabels = labels
        resetState()
        val folder = FolderMemory.findFolderFor(pendingTarget!!)
        val appPage = FolderMemory.getAppPage(pendingTarget!!)
        val folderPage = folder?.let { FolderMemory.getFolderPage(it) }
        // 记忆目标页：应用直接在桌面上 → app 页；在文件夹里 → 文件夹所在页
        memoryTargetPage = appPage ?: folderPage
        memoryTargetFolder = folder
        Log.i(TAG, "memory: folder=$folder appPage=$appPage folderPage=$folderPage")
        if (memoryTargetPage != null || memoryTargetFolder != null) {
            toast("记忆定位中${if (folder != null) "（文件夹：$folder）" else ""}…")
        }
        goHome()
    }

    private var memoryTargetPage: Int? = null
    private var memoryTargetFolder: String? = null
    private var appLabels: Set<String> = emptySet()

    private fun resetState() {
        step = 0
        bootWaitMs = 0
        folderOpen = false
        leftDone = false          // 从第一页开始：先向右翻页（回卷到第一页再前进）
        noChange = 0
        page = 0
        folderCandidates.clear()
        triedFolders.clear()
        preClickSig = ""
        folderScrolls = 0
        lastFolderSig = ""
        openedFolderDesc = null
        memoryTargetPage = null
        memoryTargetFolder = null
        needFirstPage = true
        checkPageChange = ""
        treeDumped = false
        handler.removeCallbacks(scanTick)
    }

    private fun goHome() {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        handler.postDelayed(scanTick, 600)
    }

    /** 首轮先回卷到最左页（从第一页开始全量扫） */
    private var needFirstPage = true

    private fun stopScan(msg: String?) {
        handler.removeCallbacks(scanTick)
        mode = ""
        pendingTarget = null
        folderOpen = false
        if (msg != null) toast(msg)
    }

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (mode.isEmpty()) return
            val target = pendingTarget
            if (++step > MAX_STEPS) {
                Log.w(TAG, "step limit reached")
                stopScan("定位超时${target?.let { "，未找到「$it」" } ?: ""}"); return
            }
            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString() ?: ""

            // 翻页后判定：页面变了→新页；没变→到边
            if (checkPageChange.isNotEmpty()) {
                if (root != null && isLauncherPkg(pkg)) {
                    val now = iconSig(root)
                    if (now != checkPageChange) {
                        noChange = 0
                        if (!leftDone) page++      // 向右阶段每翻一页页码+1
                        Log.d(TAG, "page flipped, now page=$page")
                    } else {
                        noChange++
                    }
                }
                checkPageChange = ""
            }

            // 等回桌面
            if (root == null || !isLauncherPkg(pkg)) {
                if (pkg.contains("personalassistant") || pkg.contains("negative")) {
                    // 负一屏：向右翻过了头=已在第一页左侧，转正常向左扫描
                    if (needFirstPage) {
                        needFirstPage = false
                        Log.d(TAG, "at first page (bounced off -1 screen)")
                        handler.postDelayed(this, 300); return
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed(this, 500); return
                }
                if (preClickSig.isNotEmpty()) {
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
                    stopScan("未检测到桌面（当前前台：$pkg）"); return
                }
                handler.postDelayed(this, 400); return
            }

            // 首轮：先一路向右滑到第一页（回卷），再开始正式扫描
            if (needFirstPage) {
                needFirstPage = false
                checkPageChange = ""
                swipeLeftToFirstPage()
                handler.postDelayed(this, 700)
                // 回卷后由 -1 屏分支或翻页判定接手
                checkPageChange = ""
                handler.postDelayed({
                    if (mode.isNotEmpty() && !needFirstPage) {
                        // 回卷动作完成后立即进入向左翻页阶段
                        postFirstPageSettle()
                    }
                }, 400)
                return
            }

            // 1. 当前树找图标
            val node = findIconNode(root, pendingTarget ?: targetForScan())
            if (node != null) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                Log.i(TAG, "found at $rect folderOpen=$folderOpen page=$page")
                // 建库模式：记录应用→页码
                if (mode == "scan") {
                    val d = node.contentDescription?.toString() ?: ""
                    FolderMemory.putAppPage(normalize(d), page)
                }
                highlight(rect)
                stopScan(if (folderOpen) "已定位（文件夹内）" else "已定位")
                return
            }

            // 2. 文件夹展开态没找到 → 向下滚动（到头判定：滚动后签名无变化）
            if (folderOpen) {
                val sigNow = iconSig(root)
                if (folderScrolls < MAX_FOLDER_SCROLLS && sigNow != lastFolderSig) {
                    lastFolderSig = sigNow
                    folderScrolls++
                    Log.d(TAG, "folder miss, scrolling ($folderScrolls/$MAX_FOLDER_SCROLLS)")
                    swipeUpInFolder()
                    handler.postDelayed(this, 800); return
                }
                Log.d(TAG, "folder exhausted, closing")
                folderOpen = false
                folderScrolls = 0
                lastFolderSig = ""
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed(this, 500); return
            }

            // 3. 上一次点击是否打开了文件夹（必须先于下一次点击）
            val sig = iconSig(root)
            if (preClickSig.isNotEmpty()) {
                if (sig != preClickSig) {
                    Log.d(TAG, "candidate opened a folder")
                    folderOpen = true
                    folderScrolls = 0
                    lastFolderSig = sig
                    val opened = openedFolderDesc
                    if (opened != null) {
                        val apps = visibleAppDescs(root)
                        FolderMemory.put(opened, apps)
                        FolderMemory.putFolderPage(opened, page)
                        Log.i(TAG, "memorize folder '$opened' page=$page: $apps")
                    }
                    preClickSig = ""
                    handler.postDelayed(this, 300); return
                }
                preClickSig = ""
            }

            // 4. 本页还有文件夹候选 → 逐个打开（按名字去重）
            if (folderCandidates.isNotEmpty()) {
                val cand = folderCandidates.removeAt(0)
                val desc = folderDescAt(root, cand)
                val name = desc?.let { normalize(it) } ?: cand.toShortString()
                if (name in triedFolders) {
                    Log.d(TAG, "folder '$name' already tried, skip")
                    handler.postDelayed(this, 100); return
                }
                triedFolders.add(name)
                preClickSig = iconSig(root)
                openedFolderDesc = desc
                Log.d(TAG, "opening folder '$desc' at $cand (${folderCandidates.size} left)")
                clickFolderLabel(cand)
                handler.postDelayed(this, FOLDER_SETTLE_MS); return
            }

            // 5. 收集本页文件夹候选
            folderCandidates = collectFolderCandidates(root)
            if (folderCandidates.isNotEmpty()) {
                Log.d(TAG, "page=$page has ${folderCandidates.size} folder candidates")
                handler.postDelayed(this, 200); return
            }

            // 6. 建库模式：记住本页应用→页码
            if (mode == "scan") {
                for (d in visibleAppDescs(root)) FolderMemory.putAppPage(normalize(d), page)
            }

            // 7. 翻页：向左=下一页（主扫描方向），到边后向右回扫兜底
            if (!leftDone) {
                if (noChange >= EDGE_THRESHOLD) {
                    leftDone = true
                    noChange = 0
                    Log.d(TAG, "right edge reached (page=$page), sweeping back left")
                    if (mode == "scan") {
                        stopScan("桌面扫描完成：${triedFolders.size} 个文件夹已建库")
                        return
                    }
                    handler.postDelayed(this, 300); return
                }
                swipe(0)
                handler.postDelayed(this, SWIPE_SETTLE_MS)
                checkPageChange = sig
                return
            } else {
                if (noChange >= EDGE_THRESHOLD) {
                    Log.w(TAG, "not found (both edges swept)")
                    stopScan("未找到「${targetRaw ?: "?"}」（可能仅在应用抽屉里）"); return
                }
                swipe(1)
                handler.postDelayed(this, SWIPE_SETTLE_MS)
                checkPageChange = sig
                return
            }
        }
    }

    /** 建库模式没有具体目标，findIconNode 传一个永不匹配的名字 */
    private fun targetForScan(): String = "\u0000scan-mode"

    private fun postFirstPageSettle() { /* 回卷由翻页判定自然接手 */ }

    /** 一路向左滑到底（回卷到第一页） */
    private fun swipeLeftToFirstPage() {
        // 连续快速 6 次向左滑，保证从任何页回到第一页
        for (i in 0 until 6) {
            handler.postDelayed({ swipe(0) }, i * 350L)
        }
    }

    /**
     * 文件夹候选 = clickable + RelativeLayout(或类名含Folder) + 近方形(0.65-1.5) + 宽≥120px。
     * 小组件（横条/大块 FrameLayout 等）与 6px 预载切片被形状/宽度过滤。
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
                val isFolderCls = cls.endsWith("RelativeLayout") || cls.contains("Folder")
                val ratio = if (r.height() > 0) r.width().toFloat() / r.height() else 0f
                val folderByStructure = isFolderCls && r.width() >= 120 &&
                        ratio in 0.65f..1.5f && onScreen(r, m.widthPixels, m.heightPixels)
                if (folderByStructure) out.add(r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        out.sortWith(compareBy({ it.top }, { it.left }))
        return out
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
        if (label == "\u0000scan-mode") return null
        val want = normalize(label)
        val m = resources.displayMetrics
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable && normalize(desc) == want) {
                val cls = n.className?.toString() ?: ""
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

    /** 点文件夹图标：大文件夹（2×2，宽>300px）点名称区（底部 8%） */
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

    /** 当前屏幕上所有应用图标 desc（用于记忆内容清单/建库页码） */
    private fun visibleAppDescs(root: AccessibilityNodeInfo): List<String> {
        val m = resources.displayMetrics
        val out = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val desc = n.contentDescription?.toString()
            if (desc != null && n.isClickable) {
                val cls = n.className?.toString() ?: ""
                if (cls.endsWith("TextView") || cls.endsWith("ImageView")) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (onScreen(r, m.widthPixels, m.heightPixels) && r.width() > 120) {
                        out.add(desc)
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return out
    }

    /** 取候选 rect 对应的文件夹名 */
    private fun folderDescAt(root: AccessibilityNodeInfo, rect: Rect): String? {
        val cx = rect.exactCenterX(); val cy = rect.exactCenterY()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.contains(cx.toInt(), cy.toInt())) {
                val cls = n.className?.toString() ?: ""
                if ((cls.endsWith("RelativeLayout") || cls.contains("Folder")) && r.width() >= 120) {
                    return n.contentDescription?.toString()
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
    }

    /** 独立小窗口高亮 */
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

    /** 红色圆角框 + 扩散脉冲环 */
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
