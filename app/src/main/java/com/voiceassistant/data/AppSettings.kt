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
    INTERNLM("internlm", "书生·浦语（InternLM）"),
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
    /** 唤醒后先用一句话应答（"我在"、"有什么吩咐"…），再开始听指令 */
    val wakeAckEnabled: Boolean = true,
    /** 欢迎词列表，一行一句；留空用内置默认 */
    val wakeAckPhrases: String = "",
    /** random = 随机，fixed = 固定用 [wakeAckFixed] */
    val wakeAckMode: String = "random",
    val wakeAckFixed: String = "",
    /** 结束语列表，一行一句；留空用内置默认 */
    val farewellPhrases: String = "",
    /** 听到「再见/结束对话」时回一句告别 */
    val farewellEnabled: Boolean = true,
    /** 唤醒词，纯文本，可随时改；改词不需要重训模型 */
    val wakeKeyword: String = DEFAULT_WAKE_KEYWORD,
    /** KWS 触发阈值 0..1，越大越不敏感 */
    val wakeThreshold: Float = 0.25f,
    /** 自定义唤醒关键词行（多行，每行一个 KWS 关键词；空表示只用模型自带 keywords.txt） */
    val wakeKeywordLines: String = "",
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
    val cloudTimeoutSec: Int = 30,
    val cloudForceJson: Boolean = true,
    val cloudAutoFallback: Boolean = true,
    /** 匹配不到指令时，让大模型直接聊天/回答（纯播报，不执行动作） */
    val chatFallbackEnabled: Boolean = true,

    // ---- 分组三：技能 ----
    val navEnabled: Boolean = false,
    val amapKey: String = "",
    /** 高德「安全密钥」(jscode)：2021-12-02 之后新申请的 Key 必填 */
    val amapSecurityKey: String = "",
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
    val floatBallEnabled: Boolean = false,
    /** 悬浮球透明度 0.2–1.0 */
    val floatBallAlpha: Float = 0.85f
) {
    val hasCloudKey: Boolean get() = cloudApiKey.isNotBlank()
    val hasAmapKey: Boolean get() = amapKey.isNotBlank()

    companion object {
        const val DEFAULT_WAKE_KEYWORD = "你好小助手"
    }
}
