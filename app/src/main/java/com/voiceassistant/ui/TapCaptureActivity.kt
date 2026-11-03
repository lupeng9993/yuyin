package com.voiceassistant.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.voiceassistant.calib.CalibStore
import com.voiceassistant.log.DebugLog

/**
 * 校准页：**几乎全透明**，底下的目标软件完全可见，只在底部显示一句小字提示。
 *
 * 用法：一次可采多个点，按顺序提示「请点搜索图标」→「请点聊天输入框」→「校准完成」。
 * 坐标存进 CalibStore（外部文件 + 私有配置），卸载重装不丢。
 */
class TapCaptureActivity : ComponentActivity() {

    private var names: List<String> = listOf(CalibStore.SEARCH)
    private var index = 0
    private var hint: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        names = intent.getStringArrayListExtra(EXTRA_NAMES)?.takeIf { it.isNotEmpty() }
            ?: listOf(CalibStore.SEARCH)
        index = 0

        // 用最朴素的 View，不引入 Compose，避免启动慢
        val root = FrameLayout(this)
        val label = TextView(this).apply {
            text = hintFor(names[0])
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x99000000.toInt())
            setPadding(28, 14, 28, 14)
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 60
        }
        root.addView(label, lp)
        hint = label

        // 背景完全透明（alpha=0），点哪都能收到事件
        root.setBackgroundColor(0x00000000)
        setContentView(root)

        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                val name = names.getOrNull(index) ?: return@setOnTouchListener true
                CalibStore.save(this, name, x, y)
                DebugLog.d("Calib", "记录 $name = ($x, $y)")
                index++
                if (index >= names.size) {
                    DebugLog.d("Calib", "校准完成")
                    finish()
                } else {
                    hint?.text = hintFor(names[index])
                }
            }
            true
        }
    }

    private fun hintFor(name: String): String = when (name) {
        CalibStore.SEARCH -> "请点一下「搜索图标」的位置"
        CalibStore.INPUT -> "请点一下「聊天输入框」的位置"
        CalibStore.SEND -> "请点一下「发送」按钮的位置"
        else -> "请点一下「$name」的位置"
    }

    companion object {
        const val EXTRA_NAMES = "names"

        fun intent(context: Context, names: List<String>): Intent =
            Intent(context, TapCaptureActivity::class.java)
                .putStringArrayListExtra(EXTRA_NAMES, ArrayList(names))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
