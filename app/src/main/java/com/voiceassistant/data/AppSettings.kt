package com.voiceassistant.data

/** TTS 引擎选择 */
enum class TtsEngine(val id: String) {
    SYSTEM("system"),
    SHERPA("sherpa");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SHERPA
    }
}

/** 云端服务商类型 */
enum class CloudProvider(val id: String, val label: String) {
    DOUBAO("doubao", "豆包（火山方舟）"),
    QWEN("qwen", "通义千问（阿里云百炼）"),
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI 兼容（通用）"),
    PRIVATE("private", "自定义私有接口");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OPENAI_COMPATIBLE
    }
}

/**
 * 全部应用配置。密钥类字段（cloudApiKey / amapKey）在存储时用 Keystore 加密。
 */
data class AppSettings(
    // ---- 分组一：唤醒与音频 ----
    val wakeEnabled: Boolean = false,
    /** 唤醒词，纯文本，可随时改；改词不需要重训模型 */
    val wakeKeyword: String = DEFAULT_WAKE_KEYWORD,
    /** KWS 触发阈值 0..1，越大越不敏感 */
    val wakeThreshold: Float = 0.25f,
    /** 自定义唤醒关键词行（KWS 原始格式，形如 "x iǎo zhù shǒu @小助手"）；空表示用模型自带 keywords.txt */
    val wakeKeywordLine: String = "",
    val wakeModelDir: String = "",
    val asrEnabled: Boolean = true,
    val asrModelDir: String = "",
    /** Silero VAD 模型文件路径（.onnx）；留空则使用内置能量 VAD */
    val vadModelPath: String = "",
    /** 语音音量（仅用于本地 VAD 判定，不影响系统音量） */
    val vadSilenceMs: Int = 600,

    // ---- 分组二：云端解析 ----
    val cloudEnabled: Boolean = false,
    val cloudProvider: CloudProvider = CloudProvider.OPENAI_COMPATIBLE,
    val cloudBaseUrl: String = "",
    val cloudModel: String = "",
    val cloudApiKey: String = "",
    val cloudTimeoutSec: Int = 20,
    val cloudForceJson: Boolean = true,
    val cloudAutoFallback: Boolean = true,

    // ---- 分组三：技能 ----
    val navEnabled: Boolean = false,
    val amapKey: String = "",
    val navConfirm: Boolean = false,
    val wechatEnabled: Boolean = false,
    val wechatForceConfirm: Boolean = true,
    val otherEnabled: Boolean = true,

    // TTS
    val ttsEnabled: Boolean = true,
    val ttsEngine: TtsEngine = TtsEngine.SHERPA,
    val ttsModelDir: String = "",
    val ttsSpeakerId: Int = 0,
    val ttsSpeed: Float = 1.0f,
    val ttsVolume: Float = 1.0f,

    // ---- 分组四：系统与调试 ----
    val debugMode: Boolean = false,
    val bootAutoStart: Boolean = false,
    val floatBallEnabled: Boolean = false
) {
    val hasCloudKey: Boolean get() = cloudApiKey.isNotBlank()
    val hasAmapKey: Boolean get() = amapKey.isNotBlank()

    companion object {
        const val DEFAULT_WAKE_KEYWORD = "你好小助手"
    }
}
