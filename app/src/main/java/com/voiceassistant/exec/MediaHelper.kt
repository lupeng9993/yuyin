package com.voiceassistant.exec

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.voiceassistant.log.DebugLog

/**
 * 媒体控制小工具。
 *
 * 用途：唤醒应答、播报时先把别的 App 的播放暂停，处理完再恢复——
 * 既避免"我说话时音乐还在响"，也减少 TTS 被麦克风收进去。
 */
object MediaHelper {

    private const val TAG = "MediaHelper"

    private fun audio(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun isMusicActive(context: Context): Boolean = try {
        audio(context).isMusicActive
    } catch (e: Exception) {
        false
    }

    fun sendKey(context: Context, keyCode: Int) {
        try {
            val am = audio(context)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            DebugLog.w(TAG, "发送媒体键失败: ${e.message}")
        }
    }

    /** 有音乐在放就暂停，返回 true 表示"我暂停过，之后需要恢复" */
    fun pauseIfPlaying(context: Context): Boolean {
        if (!isMusicActive(context)) return false
        sendKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        DebugLog.d(TAG, "已暂停媒体播放")
        return true
    }

    fun resume(context: Context) {
        sendKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        DebugLog.d(TAG, "已恢复媒体播放")
    }
}
