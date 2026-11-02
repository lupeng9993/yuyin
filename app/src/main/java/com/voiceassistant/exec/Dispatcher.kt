package com.voiceassistant.exec

import android.content.Context
import com.voiceassistant.data.AppSettings
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.ActionPlan
import com.voiceassistant.nlu.ActionType
import com.voiceassistant.nlu.VaAction
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 意图调度：检查技能开关 → 二次确认 → 分发执行 → 汇总结果。
 *
 * 多动作按顺序执行，单个动作失败不阻断后续（导航断网不影响后面调音量）。
 */
class Dispatcher(
    private val context: Context,
    private val settings: AppSettings,
    /** 语音二次确认：把问题念给用户，返回 true 表示确认 */
    private val confirm: suspend (String) -> Boolean,
    /** 导航有多个候选地点时，让上层（语音/界面）挑一个 */
    private val choosePlace: suspend (List<NavigationExecutor.Place>) -> NavigationExecutor.Place?
) {

    private companion object {
        const val TAG = "Dispatcher"
        const val ACTION_TIMEOUT = 30_000L
    }

    private val navigation = NavigationExecutor(context, settings)
    private val wechat = WechatExecutor(context)
    private val system = SystemExecutor(context, settings)

    suspend fun dispatch(plan: ActionPlan): DispatchOutcome {
        // 任何未知动作混入：整体不执行（协议铁律：不猜、不扩展）
        if (plan.actions.isEmpty()) {
            return DispatchOutcome(
                listOf(ActionResult(VaAction.unknown("空动作"), false, "没有听懂")),
                "没有听懂这条指令"
            )
        }
        if (plan.actions.any { it.type == ActionType.UNKNOWN }) {
            val reason = plan.actions.first { it.type == ActionType.UNKNOWN }.reason
            return DispatchOutcome(
                listOf(ActionResult(plan.actions.first { it.type == ActionType.UNKNOWN }, false, "无法识别该指令", reason)),
                "无法识别该指令"
            )
        }

        val results = ArrayList<ActionResult>()
        for (action in plan.actions) {
            val skill = Skill.of(action) ?: continue
            if (!isSkillEnabled(skill)) {
                results.add(ActionResult(action, false, "${skill.label}技能已关闭", "技能开关关闭"))
                continue
            }

            // 微信的确认放在执行器内部：等搜索出结果、消息输入完成后再问，
            // 这里就不再重复问一遍了
            val needsConfirm = action.type == ActionType.NAVIGATE && settings.navConfirm
            if (needsConfirm) {
                val question = "确认导航到${action.destination}吗？说确认或取消。"
                val confirmed = confirm(question)
                if (!confirmed) {
                    results.add(ActionResult(action, false, "已取消", "用户取消确认"))
                    DebugLog.d(TAG, "用户取消: ${action.summary()}")
                    continue
                }
            }

            val result = withTimeoutOrNull(ACTION_TIMEOUT) { execute(action) }
            if (result == null) {
                results.add(ActionResult(action, false, "操作超时", "30 秒内未完成"))
            } else {
                results.add(result)
            }
            DebugLog.d(TAG, "执行结果: ${result?.ok} ${result?.detail}")
        }

        val speech = results.mapNotNull { it.speech.ifBlank { null } }
            .joinToString("；")
            .ifEmpty { "没听懂这条指令" }

        return DispatchOutcome(results, speech)
    }

    private suspend fun execute(action: VaAction): ActionResult = when (action.type) {
        ActionType.NAVIGATE -> navigation.execute(action, choosePlace)
        ActionType.SEND_WECHAT -> wechat.send(action, confirm)
        ActionType.OTHER -> system.execute(action)
        ActionType.UNKNOWN -> ActionResult(action, false, "无法识别该指令", action.reason)
    }

    fun isSkillEnabled(skill: Skill): Boolean = when (skill) {
        Skill.NAVIGATE -> settings.navEnabled
        Skill.WECHAT -> settings.wechatEnabled
        Skill.OTHER -> settings.otherEnabled
    }
}
