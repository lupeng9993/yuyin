package com.voiceassistant.cloud

import com.voiceassistant.data.AppSettings
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.JsonCleaner
import com.voiceassistant.nlu.SystemPrompt

/**
 * OpenAI Chat Completions 兼容适配器。
 *
 * 覆盖：豆包（火山方舟）、通义千问、书生·浦语、各类中转网关、本地大模型网关。
 *
 * 一个现实问题：带「思考过程」的模型（比如书生 intern-latest）经常先把推理写一大堆，
 * 结果 JSON 还没出来就被 max_tokens 截断。所以这里做一次**严格重试**：
 * 第一次不设太长的输出上限，如果返回里找不到 JSON，就追加一条"只要 JSON"的指令再要一次。
 */
class OpenAiCompatibleAdapter(override val id: String = "openai_compatible") : CloudAdapter {

    private companion object {
        const val TAG = "CloudAdapter"
        const val FIRST_MAX_TOKENS = 512
        const val STRICT_MAX_TOKENS = 768
    }

    override suspend fun complete(
        text: String,
        settings: AppSettings,
        systemPrompt: String
    ): CloudCall {
        if (!settings.hasCloudKey) {
            return CloudCall(error = CloudError(CloudErrorKind.NO_CONFIG, "未填写 API Key"))
        }
        val baseUrl = settings.cloudBaseUrl.trim().trimEnd('/')
        if (baseUrl.isEmpty()) {
            return CloudCall(error = CloudError(CloudErrorKind.NO_CONFIG, "未填写 Base URL"))
        }
        val model = settings.cloudModel.trim()
        if (model.isEmpty()) {
            return CloudCall(error = CloudError(CloudErrorKind.NO_CONFIG, "未填写模型名称"))
        }

        val endpoint = "$baseUrl/chat/completions"

        // 第一次：正常请求
        var call = HttpEngine.postJson(
            endpoint, settings.cloudApiKey,
            buildBody(model, text, settings.cloudForceJson, strict = false, maxTokens = FIRST_MAX_TOKENS, systemPrompt = systemPrompt),
            settings.cloudTimeoutSec
        )

        // 有些服务商不支持 response_format，400 时去掉该字段重试一次
        if (!call.ok && call.httpCode == 400 && settings.cloudForceJson &&
            (call.rawBody.contains("response_format") || call.rawBody.contains("json_object"))
        ) {
            call = HttpEngine.postJson(
                endpoint, settings.cloudApiKey,
                buildBody(model, text, forceJson = false, strict = false, maxTokens = FIRST_MAX_TOKENS, systemPrompt = systemPrompt),
                settings.cloudTimeoutSec
            )
        }

        if (!call.ok) return call

        val first = extractContent(call.rawBody)
        // 聊天模式不要求 JSON，直接返回正文
        if (systemPrompt != SystemPrompt.TEXT) {
            return CloudCall(content = first, httpCode = call.httpCode, rawBody = call.rawBody)
        }
        if (hasJsonPayload(first)) return CloudCall(content = first, httpCode = call.httpCode, rawBody = call.rawBody)

        // 第二次：明确要求「只输出 JSON」，并给足长度
        DebugLog.w(TAG, "返回里没有 JSON（${first.length} 字符），用严格模式重试一次")
        val strictCall = HttpEngine.postJson(
            endpoint, settings.cloudApiKey,
            buildBody(model, text, settings.cloudForceJson, strict = true, maxTokens = STRICT_MAX_TOKENS, systemPrompt = systemPrompt),
            settings.cloudTimeoutSec
        )
        if (strictCall.ok) {
            val second = extractContent(strictCall.rawBody)
            if (hasJsonPayload(second)) {
                return CloudCall(content = second, httpCode = strictCall.httpCode, rawBody = strictCall.rawBody)
            }
            // 两次都没 JSON：把第二次的内容返回，让上层给出可读的失败原因
            if (second.isNotBlank()) {
                return CloudCall(content = second, httpCode = strictCall.httpCode, rawBody = strictCall.rawBody)
            }
        }

        if (first.isBlank()) {
            return CloudCall(
                error = CloudError(
                    CloudErrorKind.BAD_RESPONSE,
                    "返回里没有内容（HTTP ${call.httpCode}，原始 ${call.rawBody.length} 字节：${call.rawBody.take(160)}）"
                ),
                httpCode = call.httpCode,
                rawBody = call.rawBody
            )
        }
        return CloudCall(content = first, httpCode = call.httpCode, rawBody = call.rawBody)
    }

    /** 返回内容里到底有没有能解析的意图 JSON */
    private fun hasJsonPayload(content: String): Boolean {
        if (content.isBlank()) return false
        val cleaned = JsonCleaner.clean(content) ?: return false
        if (!cleaned.startsWith("{")) return false
        return cleaned.contains("\"action\"") || cleaned.contains("\"actions\"")
    }

    private fun buildBody(
        model: String,
        userText: String,
        forceJson: Boolean,
        strict: Boolean,
        maxTokens: Int,
        systemPrompt: String
    ): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"model\":").append(jsonString(model)).append(',')
        sb.append("\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(jsonString(systemPrompt)).append("},")
        sb.append("{\"role\":\"user\",\"content\":").append(jsonString(userText)).append("}")
        if (strict) {
            sb.append(",{\"role\":\"user\",\"content\":")
                .append(jsonString("直接输出那个 JSON 对象本身，第一个字符必须是 {，不要写思考过程、不要解释、不要代码块。"))
                .append("}")
        }
        sb.append("],")
        sb.append("\"temperature\":0,")
        sb.append("\"max_tokens\":").append(maxTokens)
        if (forceJson) {
            sb.append(",\"response_format\":{\"type\":\"json_object\"}")
        }
        sb.append('}')
        return sb.toString()
    }

    /** 极简 JSON 转义，避免为了一个字符串引入序列化插件 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 16)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 从 OpenAI 格式响应里取正文 */
    private fun extractContent(rawBody: String): String {
        return try {
            val root = org.json.JSONObject(rawBody)
            val choices = root.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            message.optString("content", "")
        } catch (e: Exception) {
            ""
        }
    }
}
