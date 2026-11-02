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

        // 静音时临时抬高媒体音量，播完立刻恢复：
        // 音乐还是静音的，但助手能说话（否则用户以为它坏了）
        val am = context.getSystemService(android.media.AudioManager::class.java)
        val before = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: -1
        val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val boosted = before == 0
        if (boosted && am != null) {
            val target = (max * 0.3f).toInt().coerceIn(1, max)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            DebugLog.d(TAG, "媒体静音中，临时抬到 $target/$max 播报")
        }

        val id = "va-${utteranceId.incrementAndGet()}"
        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    restoreVolume()
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    restoreVolume()
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    DebugLog.w(TAG, "播报失败 code=$errorCode")
                    restoreVolume()
                    if (cont.isActive) cont.resume(Unit)
                }

                private fun restoreVolume() {
                    if (boosted && am != null && before >= 0) {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, before, 0)
                        DebugLog.d(TAG, "播报结束，媒体音量恢复为 $before")
                    }
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
