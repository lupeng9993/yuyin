package com.voiceassistant.service

import com.voiceassistant.nlu.ActionPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级状态总线：服务、界面、无障碍三方共享的运行状态。
 * 只放瞬时状态，配置一律走 SettingsRepository。
 */
object AppStateBus {

    data class UiState(
        val serviceRunning: Boolean = false,
        val listening: Boolean = false,
        val paused: Boolean = false,
        val accessibilityReady: Boolean = false,
        val floatBallVisible: Boolean = false,
        val lastAsrText: String = "",
        val lastIntent: String = "",
        val lastResult: String = "",
        val lastSource: String = "",
        // 唤醒引擎状态（诊断「说了没反应」用）
        val wakeEngine: String = "",
        val wakeKeywords: List<String> = emptyList(),
        val wakeError: String = "",
        /** 悬浮球显示失败的原因（没有权限 / addView 抛异常等） */
        val floatBallError: String = "",
        // 调试面板
        val debugAsrText: String = "",
        val debugCloudRaw: String = "",
        val debugCleaned: String = "",
        val debugParseState: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun setAccessibilityReady(ready: Boolean) {
        _state.value = _state.value.copy(accessibilityReady = ready)
    }

    fun setServiceRunning(running: Boolean) {
        _state.value = _state.value.copy(
            serviceRunning = running,
            listening = running && !_state.value.paused
        )
    }

    fun setPaused(paused: Boolean) {
        _state.value = _state.value.copy(paused = paused, listening = _state.value.serviceRunning && !paused)
    }

    fun setFloatBallVisible(visible: Boolean) {
        _state.value = _state.value.copy(
            floatBallVisible = visible,
            floatBallError = if (visible) "" else _state.value.floatBallError
        )
    }

    fun setFloatBallError(reason: String) {
        _state.value = _state.value.copy(floatBallError = reason)
    }

    fun setWakeInfo(engine: String, keywords: List<String>, error: String) {
        _state.value = _state.value.copy(
            wakeEngine = engine,
            wakeKeywords = keywords,
            wakeError = error
        )
    }

    fun onRecognized(text: String, plan: ActionPlan) {
        _state.value = _state.value.copy(
            lastAsrText = text,
            lastIntent = plan.actions.joinToString(" + ") { it.summary() },
            lastSource = if (plan.source == ActionPlan.Source.CLOUD) "云端解析" else "本地规则"
        )
    }

    fun onResult(summary: String) {
        _state.value = _state.value.copy(lastResult = summary)
    }

    fun onDebug(asr: String? = null, cloudRaw: String? = null, cleaned: String? = null, parseState: String? = null) {
        val s = _state.value
        _state.value = s.copy(
            debugAsrText = asr ?: s.debugAsrText,
            debugCloudRaw = cloudRaw ?: s.debugCloudRaw,
            debugCleaned = cleaned ?: s.debugCleaned,
            debugParseState = parseState ?: s.debugParseState
        )
    }

    fun resetDebug() {
        _state.value = _state.value.copy(
            debugAsrText = "", debugCloudRaw = "", debugCleaned = "", debugParseState = ""
        )
    }
}
