package com.voiceassistant.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.vaDataStore by preferencesDataStore(name = "va_settings")

/**
 * 单一配置仓库：DataStore + Keystore 加密字段。
 *
 * 明文配置直接存 DataStore；API Key / 高德 Key 先经 [SecureStore] 加密再存。
 */
class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings: StateFlow<AppSettings> = appContext.vaDataStore.data
        .map { prefs -> decode(prefs) }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = appContext.vaDataStore.data.map { decode(it) }.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        appContext.vaDataStore.edit { prefs ->
            val next = transform(decode(prefs))
            write(prefs, next)
        }
    }

    suspend fun resetAll() {
        appContext.vaDataStore.edit { prefs ->
            prefs.clear()
            write(prefs, AppSettings())
        }
    }

    // ---------------- 编解码 ----------------

    private fun decode(prefs: Preferences): AppSettings {
        val default = AppSettings()
        return AppSettings(
            wakeEnabled = prefs[K.wakeEnabled] ?: default.wakeEnabled,
            wakeAckEnabled = prefs[K.wakeAckEnabled] ?: default.wakeAckEnabled,
            farewellEnabled = prefs[K.farewellEnabled] ?: default.farewellEnabled,
            wakeKeyword = prefs[K.wakeKeyword] ?: default.wakeKeyword,
            wakeThreshold = prefs[K.wakeThreshold] ?: default.wakeThreshold,
            wakeKeywordLines = prefs[K.wakeKeywordLines] ?: default.wakeKeywordLines,
            wakeAckPhrases = prefs[K.wakeAckPhrases] ?: default.wakeAckPhrases,
            wakeAckMode = prefs[K.wakeAckMode] ?: default.wakeAckMode,
            wakeAckFixed = prefs[K.wakeAckFixed] ?: default.wakeAckFixed,
            farewellPhrases = prefs[K.farewellPhrases] ?: default.farewellPhrases,
            wakeModelDir = prefs[K.wakeModelDir] ?: default.wakeModelDir,
            asrEnabled = prefs[K.asrEnabled] ?: default.asrEnabled,
            asrModelDir = prefs[K.asrModelDir] ?: default.asrModelDir,
            vadModelPath = prefs[K.vadModelPath] ?: default.vadModelPath,
            vadSilenceMs = prefs[K.vadSilenceMs] ?: default.vadSilenceMs,
            cloudEnabled = prefs[K.cloudEnabled] ?: default.cloudEnabled,
            cloudProvider = CloudProvider.of(prefs[K.cloudProvider]),
            cloudBaseUrl = prefs[K.cloudBaseUrl] ?: default.cloudBaseUrl,
            cloudModel = prefs[K.cloudModel] ?: default.cloudModel,
            cloudApiKey = SecureStore.decrypt(prefs[K.cloudApiKeyEnc] ?: ""),
            cloudTimeoutSec = prefs[K.cloudTimeoutSec] ?: default.cloudTimeoutSec,
            cloudForceJson = prefs[K.cloudForceJson] ?: default.cloudForceJson,
            cloudAutoFallback = prefs[K.cloudAutoFallback] ?: default.cloudAutoFallback,
            chatFallbackEnabled = prefs[K.chatFallback] ?: default.chatFallbackEnabled,
            navEnabled = prefs[K.navEnabled] ?: default.navEnabled,
            homeCity = prefs[K.homeCity] ?: default.homeCity,
            amapKey = SecureStore.decrypt(prefs[K.amapKeyEnc] ?: ""),
            amapSecurityKey = SecureStore.decrypt(prefs[K.amapSecurityEnc] ?: ""),
            navConfirm = prefs[K.navConfirm] ?: default.navConfirm,
            wechatEnabled = prefs[K.wechatEnabled] ?: default.wechatEnabled,
            wechatForceConfirm = prefs[K.wechatForceConfirm] ?: default.wechatForceConfirm,
            otherEnabled = prefs[K.otherEnabled] ?: default.otherEnabled,
            ttsEnabled = prefs[K.ttsEnabled] ?: default.ttsEnabled,
            ttsEngine = TtsEngine.of(prefs[K.ttsEngine]),
            ttsModelDir = prefs[K.ttsModelDir] ?: default.ttsModelDir,
            ttsSpeakerId = prefs[K.ttsSpeakerId] ?: default.ttsSpeakerId,
            ttsSpeed = prefs[K.ttsSpeed] ?: default.ttsSpeed,
            ttsVolume = prefs[K.ttsVolume] ?: default.ttsVolume,
            debugMode = prefs[K.debugMode] ?: default.debugMode,
            triggerMusic = prefs[K.triggerMusic] ?: default.triggerMusic,
            triggerMovie = prefs[K.triggerMovie] ?: default.triggerMovie,
            triggerReverse = prefs[K.triggerReverse] ?: default.triggerReverse,
            cloudProfilesJson = prefs[K.cloudProfiles] ?: default.cloudProfilesJson,
            activeProfileId = prefs[K.activeProfileId] ?: default.activeProfileId,
            bootAutoStart = prefs[K.bootAutoStart] ?: default.bootAutoStart,
            floatBallEnabled = prefs[K.floatBallEnabled] ?: default.floatBallEnabled,
            floatBallAlpha = prefs[K.floatBallAlpha] ?: default.floatBallAlpha
        )
    }

    private fun write(m: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        m[K.wakeEnabled] = s.wakeEnabled
        m[K.wakeAckEnabled] = s.wakeAckEnabled
        m[K.farewellEnabled] = s.farewellEnabled
        m[K.wakeKeyword] = s.wakeKeyword
        m[K.wakeThreshold] = s.wakeThreshold
        m[K.wakeKeywordLines] = s.wakeKeywordLines
        m[K.wakeAckPhrases] = s.wakeAckPhrases
        m[K.wakeAckMode] = s.wakeAckMode
        m[K.wakeAckFixed] = s.wakeAckFixed
        m[K.farewellPhrases] = s.farewellPhrases
        m[K.wakeModelDir] = s.wakeModelDir
        m[K.asrEnabled] = s.asrEnabled
        m[K.asrModelDir] = s.asrModelDir
        m[K.vadModelPath] = s.vadModelPath
        m[K.vadSilenceMs] = s.vadSilenceMs
        m[K.cloudEnabled] = s.cloudEnabled
        m[K.cloudProvider] = s.cloudProvider.id
        m[K.cloudBaseUrl] = s.cloudBaseUrl
        m[K.cloudModel] = s.cloudModel
        if (s.cloudApiKey.isNotEmpty()) m[K.cloudApiKeyEnc] = SecureStore.encrypt(s.cloudApiKey) else m.remove(K.cloudApiKeyEnc)
        m[K.cloudTimeoutSec] = s.cloudTimeoutSec
        m[K.cloudForceJson] = s.cloudForceJson
        m[K.cloudAutoFallback] = s.cloudAutoFallback
        m[K.chatFallback] = s.chatFallbackEnabled
        m[K.navEnabled] = s.navEnabled
        m[K.homeCity] = s.homeCity
        if (s.amapKey.isNotEmpty()) m[K.amapKeyEnc] = SecureStore.encrypt(s.amapKey) else m.remove(K.amapKeyEnc)
        if (s.amapSecurityKey.isNotEmpty()) m[K.amapSecurityEnc] = SecureStore.encrypt(s.amapSecurityKey)
        else m.remove(K.amapSecurityEnc)
        m[K.navConfirm] = s.navConfirm
        m[K.wechatEnabled] = s.wechatEnabled
        m[K.wechatForceConfirm] = s.wechatForceConfirm
        m[K.otherEnabled] = s.otherEnabled
        m[K.ttsEnabled] = s.ttsEnabled
        m[K.ttsEngine] = s.ttsEngine.id
        m[K.ttsModelDir] = s.ttsModelDir
        m[K.ttsSpeakerId] = s.ttsSpeakerId
        m[K.ttsSpeed] = s.ttsSpeed
        m[K.ttsVolume] = s.ttsVolume
        m[K.debugMode] = s.debugMode
        m[K.triggerMusic] = s.triggerMusic
        m[K.triggerMovie] = s.triggerMovie
        m[K.triggerReverse] = s.triggerReverse
        m[K.cloudProfiles] = s.cloudProfilesJson
        m[K.activeProfileId] = s.activeProfileId
        m[K.bootAutoStart] = s.bootAutoStart
        m[K.floatBallEnabled] = s.floatBallEnabled
        m[K.floatBallAlpha] = s.floatBallAlpha
    }

    private object K {
        val wakeEnabled = booleanPreferencesKey("wake_enabled")
        val wakeAckEnabled = booleanPreferencesKey("wake_ack_enabled")
        val farewellEnabled = booleanPreferencesKey("farewell_enabled")
        val wakeKeyword = stringPreferencesKey("wake_keyword")
        val wakeThreshold = floatPreferencesKey("wake_threshold")
        val wakeKeywordLines = stringPreferencesKey("wake_keyword_line")
        val wakeAckPhrases = stringPreferencesKey("wake_ack_phrases")
        val wakeAckMode = stringPreferencesKey("wake_ack_mode")
        val wakeAckFixed = stringPreferencesKey("wake_ack_fixed")
        val farewellPhrases = stringPreferencesKey("farewell_phrases")
        val wakeModelDir = stringPreferencesKey("wake_model_dir")
        val asrEnabled = booleanPreferencesKey("asr_enabled")
        val asrModelDir = stringPreferencesKey("asr_model_dir")
        val vadModelPath = stringPreferencesKey("vad_model_path")
        val vadSilenceMs = intPreferencesKey("vad_silence_ms")
        val cloudEnabled = booleanPreferencesKey("cloud_enabled")
        val cloudProvider = stringPreferencesKey("cloud_provider")
        val cloudBaseUrl = stringPreferencesKey("cloud_base_url")
        val cloudModel = stringPreferencesKey("cloud_model")
        val cloudApiKeyEnc = stringPreferencesKey("cloud_api_key_enc")
        val cloudTimeoutSec = intPreferencesKey("cloud_timeout_sec")
        val cloudForceJson = booleanPreferencesKey("cloud_force_json")
        val cloudAutoFallback = booleanPreferencesKey("cloud_auto_fallback")
        val chatFallback = booleanPreferencesKey("chat_fallback")
        val navEnabled = booleanPreferencesKey("nav_enabled")
        val homeCity = stringPreferencesKey("home_city")
        val amapKeyEnc = stringPreferencesKey("amap_key_enc")
        val amapSecurityEnc = stringPreferencesKey("amap_security_enc")
        val navConfirm = booleanPreferencesKey("nav_confirm")
        val wechatEnabled = booleanPreferencesKey("wechat_enabled")
        val wechatForceConfirm = booleanPreferencesKey("wechat_force_confirm")
        val otherEnabled = booleanPreferencesKey("other_enabled")
        val ttsEnabled = booleanPreferencesKey("tts_enabled")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsModelDir = stringPreferencesKey("tts_model_dir")
        val ttsSpeakerId = intPreferencesKey("tts_speaker_id")
        val ttsSpeed = floatPreferencesKey("tts_speed")
        val ttsVolume = floatPreferencesKey("tts_volume")
        val debugMode = booleanPreferencesKey("debug_mode")
        val triggerMusic = stringPreferencesKey("trigger_music")
        val triggerMovie = stringPreferencesKey("trigger_movie")
        val triggerReverse = stringPreferencesKey("trigger_reverse")
        val cloudProfiles = stringPreferencesKey("cloud_profiles")
        val activeProfileId = stringPreferencesKey("active_profile_id")
        val bootAutoStart = booleanPreferencesKey("boot_auto_start")
        val floatBallEnabled = booleanPreferencesKey("float_ball_enabled")
        val floatBallAlpha = floatPreferencesKey("float_ball_alpha")
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context).also { instance = it }
            }
    }
}
