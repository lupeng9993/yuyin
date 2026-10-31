package com.voiceassistant.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.voiceassistant.log.DebugLog
import kotlin.math.abs

/**
 * 悬浮小球：常驻监听的可见入口，也兼顾保活。
 *
 * - 半透明，透明度可在设置里调
 * - 正在听指令时边缘有淡五彩流光（极光效果），听完自动停
 * - 拖动移位置，单击暂停/继续，长按打开应用
 */
class FloatWindow(
    private val context: Context,
    private var alpha: Float,
    private val onTogglePause: () -> Unit,
    private val onOpenApp: () -> Unit
) {

    companion object {
        private const val TAG = "FloatWindow"
        private const val BALL_DP = 56
        private const val RING_DP = 90
        private const val TAP_SLOP_DP = 8
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var label: TextView? = null
    private var dot: View? = null
    private var aurora: AuroraRingView? = null
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
        val ballSize = (BALL_DP * density).toInt()
        val containerSize = (RING_DP * density).toInt()

        val container = FrameLayout(context)
        container.alpha = alpha.coerceIn(0.2f, 1f)

        // 极光层在最底下
        val ring = AuroraRingView(context)
        container.addView(ring, FrameLayout.LayoutParams(containerSize, containerSize))

        // 中间磁贴样式的小圆
        val ball = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E61B6EF3"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#99FFFFFF"))
            }
        }
        val text = TextView(context).apply {
            text = "听"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        ball.addView(text, FrameLayout.LayoutParams(ballSize, ballSize))

        val ballParams = FrameLayout.LayoutParams(ballSize, ballSize).apply {
            gravity = Gravity.CENTER
        }
        container.addView(ball, ballParams)

        // 状态小圆点
        val statusDot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF4CAF50"))
            }
        }
        val dotSize = (10 * density).toInt()
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.CENTER
            topMargin = (-(ballSize / 2 - dotSize / 2)).coerceAtMost(0)
            leftMargin = (ballSize / 2 - dotSize).coerceAtLeast(0)
        }
        container.addView(statusDot, dotParams)

        val lp = WindowManager.LayoutParams(
            containerSize,
            containerSize,
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
            x = context.resources.displayMetrics.widthPixels - containerSize - (4 * density).toInt()
            y = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = TAP_SLOP_DP * density

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
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
                        if (event.eventTime - event.downTime > 600) onOpenApp() else onTogglePause()
                    } else {
                        snapToEdge(wm, container, lp)
                    }
                    true
                }

                else -> false
            }
        }

        return try {
            wm.addView(container, lp)
            windowManager = wm
            rootView = container
            label = text
            dot = statusDot
            aurora = ring
            visible = true
            AppStateBus.setFloatBallVisible(true)
            DebugLog.d(TAG, "悬浮小球已显示 alpha=$alpha")
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "添加悬浮窗失败", e)
            false
        }
    }

    private fun snapToEdge(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        val width = context.resources.displayMetrics.widthPixels
        val density = context.resources.displayMetrics.density
        val target = if (lp.x + view.width / 2 < width / 2) {
            (-(RING_DP - BALL_DP) / 2 * density).toInt().coerceAtLeast(0)
        } else {
            width - view.width + ((RING_DP - BALL_DP) / 2 * density).toInt()
        }
        lp.x = target
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: Exception) {
            // ignore
        }
    }

    /** 监听中：绿色点 + 文字「听」；暂停：灰色 + 「停」 */
    fun setListening(listening: Boolean) {
        label?.text = if (listening) "听" else "停"
        (dot?.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (listening) "#FF4CAF50" else "#FF9E9E9E")
        )
    }

    /** 唤醒命中、正在听指令 → 边缘极光流动；指令处理完 → 立刻熄灭 */
    fun setAurora(active: Boolean) {
        aurora?.active = active
    }

    fun setAlpha(value: Float) {
        alpha = value.coerceIn(0.2f, 1f)
        rootView?.alpha = alpha
    }

    fun hide() {
        if (!visible) return
        aurora?.active = false
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            DebugLog.w(TAG, "移除悬浮窗失败: ${e.message}")
        }
        rootView = null
        label = null
        dot = null
        aurora = null
        visible = false
        AppStateBus.setFloatBallVisible(false)
    }
}

/**
 * 极光效果：在球外围画一圈柔化的五彩扫掠渐变，缓慢旋转。
 * 只在 active 时动画，其余时间完全静止，不耗电。
 */
private class AuroraRingView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private var shader: SweepGradient? = null
    private val matrix = Matrix()
    private val rect = RectF()
    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            applyRotation()
            invalidate()
        }
    }

    var active: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                if (!animator.isStarted) animator.start() else animator.resume()
            } else {
                animator.cancel()
                angle = 0f
            }
            invalidate()
        }

    init {
        // 模糊滤镜在硬件加速层上不生效，这里强制软件层（只有小球这一块）
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        visibility = View.VISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(w, h) / 2f) - 6f * density
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val colors = intArrayOf(
            0x00FF6B6B.toInt(),
            0x88FF6B6B.toInt(),
            0x8089C4FF.toInt(),
            0x809B8CFF.toInt(),
            0x80FFD166.toInt(),
            0x8072E1A5.toInt(),
            0x00FF6B6B.toInt()
        )
        shader = SweepGradient(cx, cy, colors, null)
        paint.shader = shader
        applyRotation()
    }

    private fun applyRotation() {
        val cx = width / 2f
        val cy = height / 2f
        matrix.setRotate(angle, cx, cy)
        shader?.setLocalMatrix(matrix)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return
        canvas.drawOval(rect, paint)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
