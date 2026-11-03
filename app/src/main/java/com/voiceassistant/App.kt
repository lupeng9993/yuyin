package com.voiceassistant

import android.app.Application
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.NotificationHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        NotificationHelper.ensureChannel(this)
        DebugLog.d("App", "应用启动 v${BuildConfig.VERSION_NAME}")

        // 提前注册 Shizuku 监听并在已授权时先绑好 ShellService，
        // 免得第一次说「执行命令」时还要等进程拉起来
        try {
            com.voiceassistant.shell.ShizukuShell.init(this)
        } catch (e: Throwable) {
            DebugLog.w("App", "Shizuku 初始化失败: ${e.message}")
        }
    }
}
