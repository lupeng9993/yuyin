package com.voiceassistant.ui

import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voiceassistant.log.DebugLog
import com.voiceassistant.ui.theme.VoiceAssistantTheme

/**
 * 坐标校准页：全屏半透明，你在上面点一下「搜索图标」的位置，
 * 它就把坐标存起来，之后 OcrWechatFlow 直接用这个坐标点，不再靠猜。
 *
 * 用 Activity 而不是悬浮窗：不需要「显示在其他应用上层」权限。
 */
class TapCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceAssistantTheme {
                CaptureOverlay()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            save(x, y)
            DebugLog.d("Calib", "已记录点击坐标 ($x, $y)")
            finish()
            return true
        }
        return true
    }

    private fun save(x: Int, y: Int) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }

    companion object {
        const val PREFS = "va_calib"
        const val KEY_X = "search_x"
        const val KEY_Y = "search_y"

        /** 读取已校准的搜索图标坐标；没校准过返回 null */
        fun load(context: Context): Pair<Int, Int>? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val x = p.getInt(KEY_X, -1)
            val y = p.getInt(KEY_Y, -1)
            return if (x > 0 && y > 0) x to y else null
        }
    }
}

@Composable
private fun CaptureOverlay() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "请点一下「搜索图标」的位置",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            "点完自动保存并退出，之后发微信会直接用这个坐标",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFBBBBBB),
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center
        )
    }
}
