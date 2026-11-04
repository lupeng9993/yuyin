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
    DEEPSEEK("deepseek", "DeepSeek（深度求索）"),
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
    /** 自动学习：云端解析成功执行后记成模板，下次本地直接命中 */
    val autoLearn: Boolean = true,
    /** 匹配不到指令时，让大模型直接聊天/回答（纯播报，不执行动作） */
    val chatFallbackEnabled: Boolean = true,

    // ---- 分组三：技能 ----
    val navEnabled: Boolean = false,
    /** 天气播报里的"家"城市，默认泰安 */
    val homeCity: String = "泰安",
    val amapKey: String = "",
    /** 高德「安全密钥」(jscode)：2021-12-02 之后新申请的 Key 必填 */
    val amapSecurityKey: String = "",
    val navConfirm: Boolean = false,
    /** 导航应用：auto（装了车机版就优先车机版）/ amapauto / amap / baidu */
    val navApp: String = "auto",
    val wechatEnabled: Boolean = false,
    val wechatForceConfirm: Boolean = true,
    /** 发送键相对输入框右边缘的偏移（dp），车机版和手机版不同 */
    val sendOffsetDp: String = "8",
    val otherEnabled: Boolean = true,

    // TTS
    val ttsEnabled: Boolean = true,
    val ttsEngine: TtsEngine = TtsEngine.SHERPA,
    val ttsModelDir: String = "",
    val ttsSpeakerId: Int = 0,
    val ttsSpeed: Float = 1.0f,
    val ttsVolume: Float = 1.0f,

    // ---- 分组四：触发卡片（说"音乐/电影/倒车"时启动的 App）----
    val triggerMusic: String = "",
    val triggerMovie: String = "",
    val triggerReverse: String = "",

    // ---- 多套云端配置 ----
    /** JSON 数组：保存的配置快照；当前生效的仍是上面 cloud* 那几个字段 */
    val cloudProfilesJson: String = "",
    val activeProfileId: String = "",

    // ---- 分组五：系统与调试 ----
    val debugMode: Boolean = false,
    /** 日志自动上传地址（默认指向本机服务端） */
    val logUploadUrl: String = "http://192.168.51.18:8099/upload",
    /** 模型服务端地址；非空时下载模型不再走 GitHub */
    val modelServerUrl: String = "http://192.168.51.18:8099/models",
    /** 云端配置同步地址（GET/POST /config） */
    val syncUrl: String = "http://192.168.51.18:8099/config",
    /** 同步口令：用于加密 API Key，换机后同口令可解开 */
    val syncPass: String = "",
    val autoUploadLog: Boolean = false,
    val bootAutoStart: Boolean = false,
    /** 首次权限引导是否已完成 */
    val guideDone: Boolean = false,
    /** 卸载后保留模型（模型放外部目录，需要「所有文件访问」）*/
    val keepModelsOnUninstall: Boolean = false,
    /** GitHub 配置只读地址 */
    val githubConfigUrl: String = "https://raw.githubusercontent.com/lupeng9993/yuyin/main/config/va_config.json",
    /** 启动时自动从 GitHub 拉配置 */
    val autoPullGithub: Boolean = false,
    val floatBallEnabled: Boolean = false,
    /** 悬浮球透明度 0.2–1.0 */
    val floatBallAlpha: Float = 0.85f
) {
    init {
        // 让下载器在任何时候都知道"模型服务端去哪儿找"，不用到处传 settings
        com.voiceassistant.model.ModelCatalog.serverBase = modelServerUrl
        com.voiceassistant.model.ModelRepository.allowExternal = keepModelsOnUninstall
    }

    val hasCloudKey: Boolean get() = cloudApiKey.isNotBlank()
    val hasAmapKey: Boolean get() = amapKey.isNotBlank()

    companion object {
        const val DEFAULT_WAKE_KEYWORD = "你好小助手"
    }
}
