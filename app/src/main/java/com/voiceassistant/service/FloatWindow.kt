package com.voiceassistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.voiceassistant.log.DebugLog
import kotlin.math.abs

/**
 * 悬浮小球：常驻监听的可见入口，也兼顾保活（有悬浮窗权限的进程更不容易被系统回收，
 * 同时是 Android 14+ 后台启动麦克风前台服务的豁免条件之一）。
 *
 * 交互：拖动移位置，单击暂停/继续，长按打开应用。
 */
class FloatWindow(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onOpenApp: () -> Unit
) {

    companion object {
        private const val TAG = "FloatWindow"
        private const val SIZE_DP = 52
        private const val TAP_SLOP_DP = 8
    }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var label: TextView? = null
    private var dot: View? = null
    private var visible = false

    val isVisible: Boolean get() = visible

    fun canShow(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (visible) return true
        if (!canShow()) {
            DebugLog.w(TAG, "没有悬浮窗权限，无法显示小球")
            return false
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = context.resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()

        val container = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61B6EF3"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#80FFFFFF"))
            }
        }

        val text = TextView(context).apply {
            text = "听"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (2 * density).toInt(), 0, 0)
        }
        container.addView(text, FrameLayout.LayoutParams(size, size))

        // 状态小圆点：监听中为绿色，暂停为灰色
        val statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF4CAF50"))
            }
        }
        val dotSize = (9 * density).toInt()
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = (2 * density).toInt()
            rightMargin = (2 * density).toInt()
        }
        container.addView(statusDot, dotParams)

        val lp = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - size - (12 * density).toInt()
            y = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = TAP_SLOP_DP * density
        var longPressed = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try {
                            wm.updateViewLayout(container, lp)
                        } catch (e: Exception) {
                            DebugLog.w(TAG, "更新悬浮窗位置失败: ${e.message}")
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        // 单击：切换暂停
                        if (event.eventTime - event.downTime > 600) {
                            longPressed = true
                            onOpenApp()
                        } else {
                            onTogglePause()
                        }
                    }
                    // 贴近边缘时自动吸边
                    if (dragging) snapToEdge(wm, container, lp)
                    true
                }

                else -> false
            }
        }

        return try {
            wm.addView(container, lp)
            windowManager = wm
            rootView = container
            params = lp
            label = text
            dot = statusDot
            visible = true
            AppStateBus.setFloatBallVisible(true)
            DebugLog.d(TAG, "悬浮小球已显示")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "添加悬浮窗失败", e)
            false
        }
    }

    private fun snapToEdge(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        val width = context.resources.displayMetrics.widthPixels
        val target = if (lp.x + view.width / 2 < width / 2) (8 * context.resources.displayMetrics.density).toInt()
        else width - view.width - (8 * context.resources.displayMetrics.density).toInt()
        lp.x = target
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 更新小球状态：监听中绿色，暂停灰色 */
    fun setListening(listening: Boolean) {
        label?.text = if (listening) "听" else "停"
        (dot?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (listening) "#FF4CAF50" else "#FF9E9E9E")
        )
    }

    fun hide() {
        if (!visible) return
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            DebugLog.w(TAG, "移除悬浮窗失败: ${e.message}")
        }
        rootView = null
        params = null
        label = null
        dot = null
        visible = false
        AppStateBus.setFloatBallVisible(false)
    }
}
