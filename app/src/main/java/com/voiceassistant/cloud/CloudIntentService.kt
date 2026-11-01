package com.voiceassistant.cloud

import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.CloudProvider
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.IntentParser
import com.voiceassistant.nlu.JsonCleaner
import com.voiceassistant.nlu.ParseOutcome

/**
 * 云端解析编排：选适配器 → 拿原始文本 → 清洗 → 严格解析 → 错误归类。
 */
class CloudIntentService {

    private val adapters: Map<String, CloudAdapter> = mapOf(
        CloudProvider.DOUBAO.id to OpenAiCompatibleAdapter(CloudProvider.DOUBAO.id),
        CloudProvider.QWEN.id to OpenAiCompatibleAdapter(CloudProvider.QWEN.id),
        CloudProvider.INTERNLM.id to OpenAiCompatibleAdapter(CloudProvider.INTERNLM.id),
        CloudProvider.OPENAI_COMPATIBLE.id to OpenAiCompatibleAdapter(CloudProvider.OPENAI_COMPATIBLE.id),
        // 私有协议暂用兼容实现兜底，实现自定义适配器后在这里替换即可
        CloudProvider.PRIVATE.id to OpenAiCompatibleAdapter(CloudProvider.PRIVATE.id)
    )

    data class Result(
        val outcome: ParseOutcome?,
        val error: CloudError?,
        val rawContent: String,
        val cleaned: String?
    )

    suspend fun parse(text: String, settings: AppSettings): Result {
        val adapter = adapters[settings.cloudProvider.id]
            ?: OpenAiCompatibleAdapter(settings.cloudProvider.id)

        val call = adapter.complete(text, settings)
        if (!call.ok) {
            val err = call.error ?: CloudError(CloudErrorKind.UNKNOWN, "空响应")
            DebugLog.w("Cloud", "调用失败 ${err.kind} ${err.detail}")
            return Result(outcome = null, error = err, rawContent = call.rawBody, cleaned = null)
        }

        val cleaned = JsonCleaner.clean(call.content)
        DebugLog.d("Cloud", "原始返回: ${call.content.take(500)}")

        if (cleaned == null) {
            return Result(
                outcome = ParseOutcome.Failure("返回内容里找不到 JSON", null),
                error = CloudError(CloudErrorKind.BAD_RESPONSE, "找不到 JSON 对象"),
                rawContent = call.content,
                cleaned = null
            )
        }

        val outcome = IntentParser.parse(call.content, source())
        return Result(outcome = outcome, error = null, rawContent = call.content, cleaned = cleaned)
    }

    private fun source() = com.voiceassistant.nlu.ActionPlan.Source.CLOUD

    /**
     * 闲聊 / 查询：本地规则和意图解析都没接住时，把原话丢给模型，直接取自然语言回答。
     * 注意这**不会**产生任何动作，纯播报。
     */
    private val chatPrompt = """
你是手机里的语音助手。用户会随口跟你说话或提问题。
要求：用自然的中文口语回答，控制在 60 字以内；不要用 Markdown、不要列点、不要表情符号；不要提"作为AI"这类话；没把握就直说不知道。
    """.trimIndent()

    suspend fun chat(text: String, settings: AppSettings): String? {
        val adapter = adapters[settings.cloudProvider.id]
            ?: OpenAiCompatibleAdapter(settings.cloudProvider.id)
        val call = adapter.complete(text, settings, chatPrompt)
        if (!call.ok) {
            DebugLog.w("Cloud", "闲聊调用失败 ${call.error?.kind} ${call.error?.detail}")
            return null
        }
        return call.content.trim().ifBlank { null }
    }
}
