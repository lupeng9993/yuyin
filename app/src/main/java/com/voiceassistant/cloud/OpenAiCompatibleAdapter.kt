package com.voiceassistant.cloud

import com.voiceassistant.data.AppSettings
import com.voiceassistant.nlu.SystemPrompt

/**
 * OpenAI Chat Completions 兼容适配器。
 *
 * 覆盖：豆包（火山方舟）、通义千问（兼容模式）、各类中转网关、本地大模型网关
 * （Ollama / vLLM / one-api / new-api 等）。
 *
 * 只发送已识别文本，绝不上传音频。
 */
class OpenAiCompatibleAdapter(override val id: String = "openai_compatible") : CloudAdapter {

    override suspend fun complete(text: String, settings: AppSettings): CloudCall {
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

        val body = buildBody(model, text, settings.cloudForceJson)
        var call = HttpEngine.postJson("$baseUrl/chat/completions", settings.cloudApiKey, body, settings.cloudTimeoutSec)

        // 有些服务商不支持 response_format，400 时去掉该字段重试一次
        if (!call.ok && call.httpCode == 400 && settings.cloudForceJson &&
            (call.rawBody.contains("response_format") || call.rawBody.contains("json_object"))
        ) {
            call = HttpEngine.postJson(
                "$baseUrl/chat/completions",
                settings.cloudApiKey,
                buildBody(model, text, forceJson = false),
                settings.cloudTimeoutSec
            )
        }

        if (!call.ok) return call

        val content = extractContent(call.rawBody)
        if (content.isBlank()) {
            return CloudCall(
                error = CloudError(CloudErrorKind.BAD_RESPONSE, "choices[0].message.content 为空"),
                httpCode = call.httpCode,
                rawBody = call.rawBody
            )
        }
        return CloudCall(content = content, httpCode = call.httpCode, rawBody = call.rawBody)
    }

    private fun buildBody(model: String, userText: String, forceJson: Boolean): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"model\":").append(jsonString(model)).append(',')
        sb.append("\"messages\":[")
        sb.append("{\"role\":\"system\",\"content\":").append(jsonString(SystemPrompt.TEXT)).append("},")
        sb.append("{\"role\":\"user\",\"content\":").append(jsonString(userText)).append("}")
        sb.append("],")
        sb.append("\"temperature\":0")
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

    /**
     * 从 OpenAI 格式响应里取正文。用 org.json 而不是正则，避免把 JSON 转义搞错。
     */
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
