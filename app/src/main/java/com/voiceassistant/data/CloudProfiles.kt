package com.voiceassistant.data

import com.voiceassistant.log.DebugLog
import org.json.JSONArray
import org.json.JSONObject

/** 一套云端配置的快照 */
data class CloudProfile(
    val id: String,
    val name: String,
    val providerId: String,
    val baseUrl: String,
    val model: String,
    /** 内存里是明文，落盘时用 SecureStore 加密 */
    val apiKey: String,
    val timeoutSec: Int,
    val forceJson: Boolean
)

/**
 * 多套云端配置的序列化。
 * API Key 落盘前加密，界面上永远不回显原文。
 */
object CloudProfiles {

    private const val TAG = "CloudProfiles"

    fun parse(json: String): List<CloudProfile> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CloudProfile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    providerId = o.optString("provider", "openai_compatible"),
                    baseUrl = o.optString("baseUrl"),
                    model = o.optString("model"),
                    apiKey = SecureStore.decrypt(o.optString("apiKeyEnc")),
                    timeoutSec = o.optInt("timeoutSec", 30),
                    forceJson = o.optBoolean("forceJson", true)
                )
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "解析配置失败", e)
            emptyList()
        }
    }

    fun toJson(list: List<CloudProfile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("provider", p.providerId)
                    put("baseUrl", p.baseUrl)
                    put("model", p.model)
                    put("apiKeyEnc", SecureStore.encrypt(p.apiKey))
                    put("timeoutSec", p.timeoutSec)
                    put("forceJson", p.forceJson)
                }
            )
        }
        return arr.toString()
    }
}
