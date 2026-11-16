package com.light.applocator

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 扫描状态悬浮条（TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限）：
 * 顶部居中显示"扫描中…第X页 / 定位中…"，右侧【暂停】按钮。
 * 由 LocatorAccessibilityService 在扫描开始时 show()、结束时 hide()。
 */
object ScanHud {

    @Volatile var onPauseToggle: (() -> Unit)? = null

    private var tv: TextView? = null
    private var wm: WindowManager? = null
    private var shown = false

    fun show(ctx: Context, text: String) {
        try {
            if (shown) { update(text); return }
            val m = ctx.resources.displayMetrics
            val dp = m.density
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(Color.argb(200, 20, 20, 20))
                    cornerRadius = 20 * dp
                }
            }
            val t = TextView(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(0, 0, (8 * dp).toInt(), 0)
            }
            val pause = Button(ctx).apply {
                this.text = "暂停"
                textSize = 12f
                setOnClickListener { onPauseToggle?.invoke() }
            }
            box.addView(t)
            box.addView(pause)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (8 * dp).toInt()

            val w = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            w.addView(box, params)
            tv = t
            wm = w
            shown = true
        } catch (_: Exception) {}
    }

    fun update(text: String) {
        try { tv?.text = text } catch (_: Exception) {}
    }

    fun updateButton(label: String) {
        // 简化：更新整条文本即可（update 会被调），按钮文字不单独维护
    }

    fun hide() {
        try {
            val box = tv?.parent as? android.view.View
            wm?.removeViewImmediate(box ?: return)
        } catch (_: Exception) {}
        tv = null
        wm = null
        shown = false
    }
}
