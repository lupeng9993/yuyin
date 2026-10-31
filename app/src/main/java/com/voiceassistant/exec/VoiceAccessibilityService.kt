package com.voiceassistant.exec

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.AppStateBus

/**
 * 无障碍执行服务。
 *
 * 说明：只能由用户在系统设置里手动开启，应用无法自动开启，也无法代用户授权。
 * 这里只做能力注册，具体执行逻辑在 [WechatExecutor] / [SystemExecutor]。
 */
class VoiceAccessibilityService : AccessibilityService() {

    private val TAG = "A11yService"

    override fun onServiceConnected() {
        super.onServiceConnected()
        A11yBridge.attach(this)
        AppStateBus.setAccessibilityReady(true)
        DebugLog.d(TAG, "无障碍服务已启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        A11yBridge.onWindowChanged(event)
    }

    override fun onInterrupt() {
        DebugLog.w(TAG, "无障碍服务被中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        A11yBridge.detach()
        AppStateBus.setAccessibilityReady(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        A11yBridge.detach()
        AppStateBus.setAccessibilityReady(false)
        super.onDestroy()
    }

    /** 服务连接成功即视为可用；具体节点操作失败由调用方兜底 */
    internal fun isReady(): Boolean = true
}
