package com.voiceassistant.cloud

import com.voiceassistant.data.AppSettings

/** 云端调用失败的分类，用于统一提示话术与降级判定 */
enum class CloudErrorKind(val userMessage: String) {
    NO_CONFIG("云端未配置完整"),
    NETWORK("网络不可用"),
    TIMEOUT("请求超时"),
    AUTH("API Key 无效（401）"),
    FORBIDDEN("没有访问权限（403）"),
    RATE_LIMIT("触发限流（429）"),
    SERVER("服务端错误（5xx）"),
    BAD_RESPONSE("返回内容无法解析"),
    UNKNOWN("未知错误")
}

data class CloudError(val kind: CloudErrorKind, val detail: String = "", val httpCode: Int = 0)

/** 一次云端调用的原始结果 */
data class CloudCall(
    val content: String = "",
    val error: CloudError? = null,
    val httpCode: Int = 0,
    val rawBody: String = ""
) {
    val ok: Boolean get() = error == null && content.isNotBlank()
}

/**
 * 云端适配器统一接口。
 *
 * 新增服务商只需实现这个接口并在 [CloudIntentService] 注册，主链路、规则匹配、执行调度都不用改。
 */
interface CloudAdapter {

    /** 适配器 id，与 [com.voiceassistant.data.CloudProvider.id] 对应 */
    val id: String

    /**
     * 把用户文本交给大模型，返回模型给出的原始文本（未清洗）。
     * 传输层错误一律以 [CloudCall.error] 返回，不抛异常给上层。
     */
    suspend fun complete(text: String, settings: AppSettings): CloudCall
}
