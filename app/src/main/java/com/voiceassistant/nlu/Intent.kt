package com.voiceassistant.nlu

/**
 * 意图动作类型。协议只允许这四种，任何解析结果超出范围一律判定为 UNKNOWN。
 */
enum class ActionType(val wire: String) {
    NAVIGATE("navigate"),
    SEND_WECHAT("send_wechat"),
    OTHER("other"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): ActionType? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 单个动作。字段按类型生效：
 * - NAVIGATE: destination
 * - SEND_WECHAT: contact, message
 * - OTHER: rawText（由系统执行器再做本地细分解析）
 * - UNKNOWN: reason
 */
data class VaAction(
    val type: ActionType,
    val destination: String = "",
    val contact: String = "",
    val message: String = "",
    val rawText: String = "",
    val reason: String = ""
) {
    /** 用于日志与结果卡的简短描述 */
    fun summary(): String = when (type) {
        ActionType.NAVIGATE -> "导航到 $destination"
        ActionType.SEND_WECHAT -> "发微信给 $contact：$message"
        ActionType.OTHER -> rawText
        ActionType.UNKNOWN -> "无法识别：$reason"
    }

    companion object {
        fun unknown(reason: String) = VaAction(ActionType.UNKNOWN, reason = reason)
    }
}

/**
 * 一次识别得到的一组动作。上限 [MAX_ACTIONS] = 5。
 */
data class ActionPlan(val actions: List<VaAction>, val source: Source) {

    enum class Source { LOCAL_RULE, CLOUD }

    val isUnknown: Boolean get() = actions.isEmpty() || actions.any { it.type == ActionType.UNKNOWN }

    companion object {
        const val MAX_ACTIONS = 5

        fun single(action: VaAction, source: Source) = ActionPlan(listOf(action), source)

        fun unknown(reason: String, source: Source) =
            ActionPlan(listOf(VaAction.unknown(reason)), source)
    }
}

/** 解析失败原因，用于调试面板与提示话术 */
sealed interface ParseOutcome {
    data class Success(val plan: ActionPlan, val cleaned: String) : ParseOutcome
    data class Failure(val reason: String, val cleaned: String?) : ParseOutcome
}
