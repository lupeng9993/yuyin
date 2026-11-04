package com.voiceassistant.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.voiceassistant.util.SystemSettings

/**
 * 首次启动的权限引导页：把需要「手动开」的几项集中在一页，点一次配完。
 *
 * 注意：悬浮窗 / 所有文件访问 / 无障碍 / Shizuku 授权这几项，
 * Android 不允许弹窗直接授予，只能跳系统设置，所以这里做成清单 + 跳转按钮。
 */
class PermissionGuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "先把这几项开好，之后就不用到处找了"
            textSize = 22f
            setTextColor(0xFF222222.toInt())
        })

        val items = listOf(
            "通知权限（提醒、状态栏）" to { SystemSettings.open(this, SystemSettings.notificationIntent(this)) },
            "悬浮窗权限（悬浮小球）" to { SystemSettings.open(this, SystemSettings.overlayIntent(this)) },
            "所有文件访问（模型放外部，卸载不丢）" to {
                runCatching {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                }
            },
            "无障碍服务（读界面、点按钮）" to { SystemSettings.open(this, SystemSettings.accessibilityIntent()) }
        )

        items.forEach { (label, action) ->
            root.addView(Button(this).apply {
                text = "去开启：$label"
                textSize = 16f
                setOnClickListener { runCatching(action) }
            })
        }

        root.addView(TextView(this).apply {
            text = "Shizuku 授权在应用内「设置 → 系统与调试 → 授权 Shizuku」里点一次即可。\n\n这些都可以稍后再配，直接点下面的「跳过」也能用。"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 32, 0, 32)
        })

        root.addView(Button(this).apply {
            text = "跳过，直接使用"
            setOnClickListener {
                markDone()
                finish()
            }
        })

        root.addView(Button(this).apply {
            text = "已开好，进入应用"
            setOnClickListener {
                markDone()
                startActivity(Intent(this@PermissionGuideActivity, MainActivity::class.java))
                finish()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun markDone() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    companion object {
        const val PREFS = "va_guide"
        const val KEY = "done"

        fun isDone(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

        fun intent(context: Context): Intent =
            Intent(context, PermissionGuideActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
