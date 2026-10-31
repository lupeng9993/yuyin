package com.voiceassistant.nlu

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 云端 / 规则产出的 JSON 严格解析器。
 *
 * 兼容两种形态：
 * 1. 新协议 {"actions":[{...},{...}]}
 * 2. 旧协议 {"action":"navigate", ...}（部分模型不守格式时的兜底）
 *
 * 任何字段缺失、类型错误、动作越界，都降级为 unknown，不猜测。
 */
object IntentParser {

    fun parse(raw: String, source: ActionPlan.Source): ParseOutcome {
        val cleaned = JsonCleaner.clean(raw)
            ?: return ParseOutcome.Failure("返回内容里找不到 JSON 对象", null)

        return try {
            val root = JSONObject(cleaned)
            val list: List<VaAction> = when {
                root.has("actions") -> {
                    val arr = root.optJSONArray("actions")
                        ?: return ParseOutcome.Failure("actions 不是数组", cleaned)
                    parseArray(arr)
                }
                root.has("action") -> listOf(parseOne(root))
                else -> return ParseOutcome.Failure("JSON 里既没有 actions 也没有 action", cleaned)
            }

            when {
                list.isEmpty() -> ParseOutcome.Failure("动作列表为空", cleaned)
                else -> {
                    // 超过上限只保留前 5 个（与提示词约定一致）
                    val capped = list.take(ActionPlan.MAX_ACTIONS)
                    ParseOutcome.Success(ActionPlan(capped, source), cleaned)
                }
            }
        } catch (e: JSONException) {
            ParseOutcome.Failure("JSON 语法错误：${e.message}", cleaned)
        }
    }

    private fun parseArray(arr: JSONArray): List<VaAction> {
        val out = ArrayList<VaAction>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            out.add(parseOne(obj))
        }
        return out
    }

    private fun parseOne(obj: JSONObject): VaAction {
        val wire = obj.optString("action", "").trim()
        val type = ActionType.fromWire(canonicalAction(wire))
            ?: return VaAction.unknown("不支持的动作类型：${wire.ifEmpty { "空" }}")

        return when (type) {
            ActionType.NAVIGATE -> {
                val dest = optAny(obj, "destination", "place", "to", "target", "address")
                if (dest.isEmpty()) VaAction.unknown("缺少目的地")
                else VaAction(ActionType.NAVIGATE, destination = dest)
            }

            ActionType.SEND_WECHAT -> {
                val contact = optAny(obj, "contact", "name", "to", "receiver")
                val message = optAny(obj, "message", "content", "text", "msg")
                when {
                    contact.isEmpty() -> VaAction.unknown("缺少联系人")
                    message.isEmpty() -> VaAction.unknown("缺少消息内容")
                    else -> VaAction(ActionType.SEND_WECHAT, contact = contact, message = message)
                }
            }

            ActionType.OTHER -> {
                var rawText = optAny(obj, "raw_text", "raw", "text", "command", "content", "cmd")
                if (rawText.isEmpty()) {
                    // 有些模型给结构化字段，例如 {"action":"open_app","app":"抖音"}
                    val app = optAny(obj, "app", "application", "target", "name")
                    if (app.isNotEmpty()) rawText = "打开$app"
                }
                if (rawText.isEmpty()) VaAction.unknown("缺少指令内容")
                else VaAction(ActionType.OTHER, rawText = rawText)
            }

            ActionType.UNKNOWN -> {
                val reason = obj.optString("reason", "").trim()
                VaAction.unknown(reason.ifEmpty { "无法识别该指令" })
            }
        }
    }

    /**
     * 模型有时不按约定写动作名（open_app / navigate_to / send_message…）。
     * 这里做一次同义词归一，仍然只落到那四种动作上，不新增能力。
     */
    private fun canonicalAction(raw: String): String {
        val key = raw.lowercase().trim().replace('-', '_').replace(' ', '_')
        return when (key) {
            "open_app", "openapp", "launch", "launch_app", "open", "start_app",
            "system_control", "device_control", "control" -> "other"
            "navigate_to", "navigation", "route", "goto" -> "navigate"
            "send_message", "sendmsg", "send_msg", "send_wechat_message",
            "wechat", "wechat_message", "send_wechat_msg" -> "send_wechat"
            "none", "unknown_command" -> "unknown"
            else -> key
        }
    }

    /** 依次尝试多个字段名，取第一个非空的 */
    private fun optAny(obj: JSONObject, vararg names: String): String {
        for (name in names) {
            val value = obj.optString(name, "")
            if (value.isNotBlank()) return value.trim()
        }
        return ""
    }
}

/**
 * 清洗大模型返回：去掉代码围栏、前后说明文字，只留最外层 JSON 对象。
 */
object JsonCleaner {

    private val fenceRegex = Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```")

    fun clean(raw: String): String? {
        if (raw.isBlank()) return null
        var text = raw

        val fence = fenceRegex.find(text)
        if (fence != null) {
            text = fence.groupValues[1]
        }

        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null

        return text.substring(start, end + 1).trim()
    }
}
