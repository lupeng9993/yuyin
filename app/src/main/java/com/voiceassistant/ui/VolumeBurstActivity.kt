package com.voiceassistant.ui

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.voiceassistant.log.DebugLog

/**
 * 调音量的「前台一闪」界面。
 *
 * 背景：Android 会忽略**后台应用**改音量的请求（实测：进入助手界面时能改，退回后台就 72→72 不动）。
 * 这个界面是透明的、1ms 就关，作用只是让系统认为"应用在前台"，然后把音量改掉再退出。
 *
 * 不画任何内容，用户最多看到极短的一次切换。
 */
class VolumeBurstActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val am = getSystemService(AudioManager::class.java)
        val direction = intent.getIntExtra("direction", 0)
        val absolute = intent.getIntExtra("level", -1)

        try {
            if (am != null) {
                if (absolute >= 0) {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        absolute.coerceIn(0, max),
                        AudioManager.FLAG_SHOW_UI
                    )
                    DebugLog.d(TAG, "前台一闪：绝对音量设为 $absolute")
                } else if (direction != 0) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        direction,
                        AudioManager.FLAG_SHOW_UI
                    )
                    DebugLog.d(TAG, "前台一闪：调整方向 $direction")
                }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "前台一闪改音量失败: ${e.message}")
        }

        finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val TAG = "VolumeBurst"
    }
}
