package com.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.voiceassistant.data.SettingsRepository
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机自启接收器。
 *
 * 注意：Android 14 起，麦克风类型的前台服务不允许从 BOOT_COMPLETED 直接启动，
 * 只有「已授予悬浮窗权限」等少数情况属于豁免。所以这里：
 * 1. 只有用户在设置里打开「开机自启」才尝试；
 * 2. 尝试失败就静默放弃，不做任何骚扰式提示；
 * 3. 真正的可靠做法仍是让用户手动打开一次 App。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        DebugLog.d("BootReceiver", "收到 $action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository.get(context).current()
                if (!settings.bootAutoStart) {
                    DebugLog.d("BootReceiver", "开机自启未开启，忽略")
                    return@launch
                }
                if (!settings.wakeEnabled) {
                    DebugLog.d("BootReceiver", "唤醒总开关未开，忽略")
                    return@launch
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !android.provider.Settings.canDrawOverlays(context)
                ) {
                    DebugLog.w("BootReceiver", "没有悬浮窗权限，Android 14+ 无法后台启动麦克风前台服务，放弃")
                    return@launch
                }
                val serviceIntent = Intent(context, WakeService::class.java)
                    .setAction(WakeService.ACTION_START)
                ContextCompat.startForegroundService(context, serviceIntent)
                DebugLog.d("BootReceiver", "已尝试启动监听服务")
            } catch (e: Exception) {
                DebugLog.e("BootReceiver", "开机自启失败", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
