package com.voiceassistant.cloud

import com.voiceassistant.data.CloudProvider

/**
 * 预置服务商默认值：只填地址和默认模型，密钥由用户自己申请填写。
 * 预置值只是初始值，用户可以任意改写（备用地址、中转网关都行）。
 */
data class ProviderPreset(
    val provider: CloudProvider,
    val baseUrl: String,
    val model: String,
    val hint: String
)

object ProviderPresets {

    private val presets = mapOf(
        CloudProvider.DOUBAO to ProviderPreset(
            provider = CloudProvider.DOUBAO,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            // 火山方舟用「推理接入点 ID」，形如 ep-2024xxxx-xxxxx，必须在控制台自建
            model = "",
            hint = "地址填方舟兼容地址；模型名填推理接入点 ID（ep- 开头），需要在方舟控制台创建接入点并绑定模型。"
        ),
        CloudProvider.QWEN to ProviderPreset(
            provider = CloudProvider.QWEN,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
            hint = "百炼兼容模式地址；模型可选 qwen-plus / qwen-turbo / qwen-max。"
        ),
        CloudProvider.OPENAI_COMPATIBLE to ProviderPreset(
            provider = CloudProvider.OPENAI_COMPATIBLE,
            baseUrl = "",
            model = "",
            hint = "任何支持 /v1/chat/completions 的地址，例如中转网关、局域网 vLLM、Ollama（http://IP:11434/v1）。"
        ),
        CloudProvider.PRIVATE to ProviderPreset(
            provider = CloudProvider.PRIVATE,
            baseUrl = "",
            model = "",
            hint = "私有协议平台需要自行实现 CloudAdapter 并在 CloudIntentService 注册；默认回退到 OpenAI 兼容实现。"
        )
    )

    fun of(provider: CloudProvider): ProviderPreset = presets[provider] ?: presets[CloudProvider.OPENAI_COMPATIBLE]!!
}
