package com.voiceassistant.sync

import android.content.Context
import android.util.Base64
import com.voiceassistant.data.AppSettings
import com.voiceassistant.data.CloudProvider
import com.voiceassistant.data.TtsEngine
import com.voiceassistant.log.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置云端同步。
 *
 * 设计：
 * - 所有开关/地址/词表/城市等**明文字段**直接存 JSON
 * - API Key 属于敏感项：用「同步口令」派生密钥后 AES-GCM 加密再上传，
 *   换机后输入同一口令即可解开（Keystore 加密做不到跨设备，所以这里单独处理）
 * - 服务端只存一个 va_config.json，POST /config 覆盖，GET /config 取回
 */
object ConfigSync {

    private const val TAG = "ConfigSync"
    private const val ITERATIONS = 20_000
    private const val KEY_BITS = 256

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // ---------------- 上传 ----------------

    suspend fun upload(context: Context, settings: AppSettings): String = withContext(Dispatchers.IO) {
        val url = settings.syncUrl.trim()
        if (url.isEmpty()) return@withContext "没有填写同步地址"
        if (!url.startsWith("http")) return@withContext "同步地址要以 http:// 或 https:// 开头"

        val body = buildJson(settings)
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                val msg = if (resp.isSuccessful) "配置已上传（${resp.code}）" else "上传失败 HTTP ${resp.code}"
                DebugLog.d(TAG, msg)
                msg
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "上传异常: ${e.message}")
            "上传失败：${e.message}"
        }
    }

    // ---------------- 下载 ----------------

    suspend fun download(context: Context, settings: AppSettings): Pair<String, AppSettings?> =
        withContext(Dispatchers.IO) {
            val url = settings.syncUrl.trim()
            if (url.isEmpty()) return@withContext "没有填写同步地址" to null
            if (!url.startsWith("http")) return@withContext "同步地址要以 http:// 或 https:// 开头" to null

            try {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (resp.code == 404) return@withContext "服务端还没有备份过配置" to null
                    if (!resp.isSuccessful) return@withContext "下载失败 HTTP ${resp.code}" to null
                    val text = resp.body?.string().orEmpty()
                    val parsed = applyJson(text, settings)
                        ?: return@withContext "配置格式不对，无法应用" to null
                    DebugLog.d(TAG, "配置已下载并应用")
                    "配置已下载并应用" to parsed
                }
            } catch (e: Exception) {
                "下载失败：${e.message}" to null
            }
        }

    // ---------------- JSON ----------------

    private fun buildJson(s: AppSettings): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("time", System.currentTimeMillis())

        // 唤醒
        o.put("wakePhrase", s.wakePhrase)
        o.put("threshold", s.wakeThreshold)
        o.put("kwsModelDir", s.kwsModelDir)
        o.put("asrModelDir", s.asrModelDir)
        o.put("vadModelPath", s.vadModelPath)
        o.put("vadSilenceMs", s.vadSilenceMs)

        // TTS
        o.put("ttsEnabled", s.ttsEnabled)
        o.put("ttsEngine", s.ttsEngine.id)
        o.put("ttsVolume", s.ttsVolume)
        o.put("ttsSpeed", s.ttsSpeed)

        // 云端
        o.put("cloudEnabled", s.cloudEnabled)
        o.put("cloudProvider", s.cloudProvider.id)
        o.put("cloudBaseUrl", s.cloudBaseUrl)
        o.put("cloudModel", s.cloudModel)
        o.put("cloudTimeoutSec", s.cloudTimeoutSec)
        o.put("cloudForceJson", s.cloudForceJson)
        o.put("cloudAutoFallback", s.cloudAutoFallback)
        o.put("autoLearn", s.autoLearn)
        o.put("chatFallback", s.chatFallbackEnabled)
        o.put("cloudProfilesJson", s.cloudProfilesJson)
        o.put("activeProfileId", s.activeProfileId)

        // 技能
        o.put("navEnabled", s.navEnabled)
        o.put("homeCity", s.homeCity)
        o.put("navConfirm", s.navConfirm)
        o.put("navApp", s.navApp)
        o.put("wechatEnabled", s.wechatEnabled)
        o.put("wechatForceConfirm", s.wechatForceConfirm)
        o.put("otherEnabled", s.otherEnabled)
        o.put("sendOffsetDp", s.sendOffsetDp)
        o.put("triggerMusic", s.triggerMusic)
        o.put("triggerMovie", s.triggerMovie)
        o.put("triggerReverse", s.triggerReverse)

        // 行为
        o.put("greetingEnabled", s.greetingEnabled)
        o.put("bootAutoStart", s.bootAutoStart)
        o.put("floatBallEnabled", s.floatBallEnabled)
        o.put("floatBallAlpha", s.floatBallAlpha)
        o.put("debugMode", s.debugMode)

        // 敏感项：口令为空则不迁移 Key
        if (s.syncPass.isNotBlank()) {
            val keys = JSONObject().apply {
                put("cloudApiKey", encrypt(s.cloudApiKey, s.syncPass))
                put("amapKey", encrypt(s.amapKey, s.syncPass))
                put("amapSecurityKey", encrypt(s.amapSecurityKey, s.syncPass))
            }
            o.put("keys", keys)
        }
        return o.toString()
    }

    private fun applyJson(text: String, current: AppSettings): AppSettings? = try {
        val o = JSONObject(text)
        var s = current.copy(
            wakePhrase = o.optString("wakePhrase", current.wakePhrase),
            wakeThreshold = o.optDouble("threshold", current.wakeThreshold.toDouble()).toFloat(),
            kwsModelDir = o.optString("kwsModelDir", current.kwsModelDir),
            asrModelDir = o.optString("asrModelDir", current.asrModelDir),
            vadModelPath = o.optString("vadModelPath", current.vadModelPath),
            vadSilenceMs = o.optInt("vadSilenceMs", current.vadSilenceMs),
            ttsEnabled = o.optBoolean("ttsEnabled", current.ttsEnabled),
            ttsEngine = TtsEngine.of(o.optString("ttsEngine")),
            ttsVolume = o.optDouble("ttsVolume", current.ttsVolume.toDouble()).toFloat(),
            ttsSpeed = o.optDouble("ttsSpeed", current.ttsSpeed.toDouble()).toFloat(),
            cloudEnabled = o.optBoolean("cloudEnabled", current.cloudEnabled),
            cloudProvider = CloudProvider.of(o.optString("cloudProvider")),
            cloudBaseUrl = o.optString("cloudBaseUrl", current.cloudBaseUrl),
            cloudModel = o.optString("cloudModel", current.cloudModel),
            cloudTimeoutSec = o.optInt("cloudTimeoutSec", current.cloudTimeoutSec),
            cloudForceJson = o.optBoolean("cloudForceJson", current.cloudForceJson),
            cloudAutoFallback = o.optBoolean("cloudAutoFallback", current.cloudAutoFallback),
            autoLearn = o.optBoolean("autoLearn", current.autoLearn),
            chatFallbackEnabled = o.optBoolean("chatFallback", current.chatFallbackEnabled),
            cloudProfilesJson = o.optString("cloudProfilesJson", current.cloudProfilesJson),
            activeProfileId = o.optString("activeProfileId", current.activeProfileId),
            navEnabled = o.optBoolean("navEnabled", current.navEnabled),
            homeCity = o.optString("homeCity", current.homeCity),
            navConfirm = o.optBoolean("navConfirm", current.navConfirm),
            navApp = o.optString("navApp", current.navApp),
            wechatEnabled = o.optBoolean("wechatEnabled", current.wechatEnabled),
            wechatForceConfirm = o.optBoolean("wechatForceConfirm", current.wechatForceConfirm),
            otherEnabled = o.optBoolean("otherEnabled", current.otherEnabled),
            sendOffsetDp = o.optString("sendOffsetDp", current.sendOffsetDp),
            triggerMusic = o.optString("triggerMusic", current.triggerMusic),
            triggerMovie = o.optString("triggerMovie", current.triggerMovie),
            triggerReverse = o.optString("triggerReverse", current.triggerReverse),
            greetingEnabled = o.optBoolean("greetingEnabled", current.greetingEnabled),
            bootAutoStart = o.optBoolean("bootAutoStart", current.bootAutoStart),
            floatBallEnabled = o.optBoolean("floatBallEnabled", current.floatBallEnabled),
            floatBallAlpha = o.optDouble("floatBallAlpha", current.floatBallAlpha.toDouble()).toFloat(),
            debugMode = o.optBoolean("debugMode", current.debugMode)
        )

        // Key：口令一致才解得开
        val keys = o.optJSONObject("keys")
        if (keys != null && current.syncPass.isNotBlank()) {
            val cloud = decrypt(keys.optString("cloudApiKey"), current.syncPass)
            val amap = decrypt(keys.optString("amapKey"), current.syncPass)
            val sec = decrypt(keys.optString("amapSecurityKey"), current.syncPass)
            s = s.copy(cloudApiKey = cloud, amapKey = amap, amapSecurityKey = sec)
        }
        s
    } catch (e: Exception) {
        DebugLog.e(TAG, "解析配置失败", e)
        null
    }

    // ---------------- 加解密 ----------------

    private fun deriveKey(pass: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encrypt(plain: String, pass: String): String {
        if (plain.isBlank()) return ""
        return try {
            val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(salt + iv + enc, Base64.NO_WRAP)
        } catch (e: Exception) {
            DebugLog.w(TAG, "加密失败: ${e.message}")
            ""
        }
    }

    private fun decrypt(data: String, pass: String): String {
        if (data.isBlank()) return ""
        return try {
            val all = Base64.decode(data, Base64.NO_WRAP)
            val salt = all.copyOfRange(0, 16)
            val iv = all.copyOfRange(16, 28)
            val enc = all.copyOfRange(28, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(pass, salt), GCMParameterSpec(128, iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (e: Exception) {
            DebugLog.w(TAG, "解密失败（口令不对？）: ${e.message}")
            ""
        }
    }
}
