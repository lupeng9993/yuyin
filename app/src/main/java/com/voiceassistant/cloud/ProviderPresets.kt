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
        CloudProvider.DEEPSEEK to ProviderPreset(
            provider = CloudProvider.DEEPSEEK,
            // 注意必须带 /v1：不带的话我们拼出来的 /chat/completions 会 404
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            hint = "地址必须是 https://api.deepseek.com/v1（带 /v1）。模型用 deepseek-chat（快）或 " +
                "deepseek-reasoner（会先输出思考过程，慢但更准）。支持强制 JSON。"
        ),
        CloudProvider.INTERNLM to ProviderPreset(
            provider = CloudProvider.INTERNLM,
            baseUrl = "https://chat.intern-ai.org.cn/api/v1",
            model = "intern-latest",
            hint = "书生·浦语官方接口。它不支持 response_format 参数，所以「启用强制 JSON」会自动关闭；" +
                "限流为每人每分钟 30 次，token 过期会报 401，需要去平台重新生成。"
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
