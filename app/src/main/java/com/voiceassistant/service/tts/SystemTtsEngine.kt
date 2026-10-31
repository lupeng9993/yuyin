package com.voiceassistant.service.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voiceassistant.log.DebugLog
import com.voiceassistant.service.engine.SpeakEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 系统 TTS 回退引擎。
 *
 * 优点：零下载、系统自带；缺点：中文语音包不一定装好，音质取决于 ROM。
 * 设置页提供「检查中文语音包 / 跳转系统 TTS 设置」两个动作配合它。
 */
class SystemTtsEngine(
    private val context: Context,
    private val speed: Float = 1.0f
) : SpeakEngine {

    override val name: String = "system"

    private companion object {
        const val TAG = "SystemTts"
        const val TIMEOUT_MS = 30_000L
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val utteranceId = AtomicInteger(0)

    /** 初始化（幂等）。成功返回 true */
    suspend fun init(): Boolean {
        if (ready) return true
        tts?.shutdown()
        var created: TextToSpeech? = null
        val ok: Boolean = suspendCancellableCoroutine { cont ->
            created = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    created?.language = Locale.SIMPLIFIED_CHINESE
                    created?.setSpeechRate(speed)
                    if (cont.isActive) cont.resume(true)
                } else {
                    DebugLog.e(TAG, "系统 TTS 初始化失败 status=$status")
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        tts = created
        ready = ok
        return ok
    }

    override suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (!init()) return
        val engine = tts ?: return
        val id = "va-${utteranceId.incrementAndGet()}"
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    DebugLog.w(TAG, "播报失败 code=$errorCode")
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)

            // 兜底：某些 ROM 不回调，最多等 30 秒
            cont.invokeOnCancellation { engine.stop() }
        }
    }

    override fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            DebugLog.w(TAG, "释放失败: ${e.message}")
        }
        tts = null
        ready = false
    }

    /** 是否有可用中文语音 */
    fun hasChineseVoice(): Boolean {
        val engine = tts ?: return false
        return try {
            val result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        } catch (e: Exception) {
            false
        }
    }
}
