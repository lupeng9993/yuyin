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
    }
}
