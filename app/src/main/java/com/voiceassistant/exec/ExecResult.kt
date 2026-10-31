package com.voiceassistant.exec

import com.voiceassistant.nlu.VaAction

/** 单个动作的执行结果 */
data class ActionResult(
    val action: VaAction,
    val ok: Boolean,
    /** 要播报给用户的话；空表示不播报 */
    val speech: String = "",
    /** 调试/结果卡用的细节 */
    val detail: String = ""
)

/** 一次方案（可能多个动作）的执行结果 */
data class DispatchOutcome(
    val results: List<ActionResult>,
    /** 合并后的播报文本 */
    val speech: String
) {
    val allOk: Boolean get() = results.isNotEmpty() && results.all { it.ok }
}

/** 技能开关 */
enum class Skill(val label: String) {
    NAVIGATE("导航"),
    WECHAT("微信发送"),
    OTHER("应用与系统控制");

    companion object {
        fun of(action: VaAction): Skill? = when (action.type) {
            com.voiceassistant.nlu.ActionType.NAVIGATE -> NAVIGATE
            com.voiceassistant.nlu.ActionType.SEND_WECHAT -> WECHAT
            com.voiceassistant.nlu.ActionType.OTHER -> OTHER
            com.voiceassistant.nlu.ActionType.UNKNOWN -> null
        }
    }
}
