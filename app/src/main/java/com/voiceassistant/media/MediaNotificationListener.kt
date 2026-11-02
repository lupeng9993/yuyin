package com.voiceassistant.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voiceassistant.log.DebugLog

/**
 * 媒体控制桥：通过「通知使用权」拿到当前播放器的 MediaController，
 * 这样 pause / play / next / previous 才真的有效
 * （AudioManager.dispatchMediaKeyEvent 从 Android 6 起对普通应用基本无效）。
 *
 * 没有授权时所有方法返回 false，调用方回落旧方案。
 */
object MediaBridge {

    private const val TAG = "MediaBridge"

    @Volatile
    private var controller: MediaController? = null

    fun attach(c: MediaController?) {
        controller = c
        DebugLog.d(TAG, "当前媒体控制器: ${c?.packageName ?: "无"}")
    }

    fun hasController(): Boolean = controller != null

    fun controllerPackage(): String = controller?.packageName.orEmpty()

    fun playPause(): Boolean {
        val c = controller ?: return false
        return try {
            val state = c.playbackState?.state ?: PlaybackState.STATE_NONE
            val actions = c.playbackState?.actions ?: 0L
            when (state) {
                PlaybackState.STATE_PLAYING -> {
                    if (actions and PlaybackState.ACTION_PAUSE != 0L) c.transportControls.pause() else c.transportControls.stop()
                    true
                }
                else -> {
                    c.transportControls.play()
                    true
                }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "播放控制失败: ${e.message}")
            false
        }
    }

    fun pause(): Boolean = try {
        controller?.transportControls?.pause()
        controller != null
    } catch (e: Exception) {
        false
    }

    fun play(): Boolean = try {
        controller?.transportControls?.play()
        controller != null
    } catch (e: Exception) {
        false
    }

    fun next(): Boolean = try {
        controller?.transportControls?.skipToNext()
        controller != null
    } catch (e: Exception) {
        false
    }

    fun previous(): Boolean = try {
        controller?.transportControls?.skipToPrevious()
        controller != null
    } catch (e: Exception) {
        false
    }

    /** 让播放器自己调音量（有些车机系统流被硬件固定，但播放器内部音量还能调） */
    fun adjustVolume(up: Boolean): Boolean = try {
        val c = controller ?: return false
        c.adjustVolume(
            if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
            android.media.AudioManager.FLAG_SHOW_UI
        )
        true
    } catch (e: Exception) {
        DebugLog.w(TAG, "调节播放器音量失败: ${e.message}")
        false
    }
}

/**
 * 需要用户在系统设置里手动开启「通知使用权」。
 * 开启后我们才能枚举其它 App 的媒体会话。
 */
class MediaNotificationListener : NotificationListenerService() {

    private companion object {
        const val TAG = "MediaListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        DebugLog.d(TAG, "通知使用权已连接")
        refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        refresh()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        MediaBridge.attach(null)
    }

    private fun refresh() {
        try {
            val manager = getSystemService(MediaSessionManager::class.java) ?: return
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val sessions = manager.getActiveSessions(component)
            val active = sessions.firstOrNull { s ->
                val state = s.playbackState?.state ?: PlaybackState.STATE_NONE
                state == PlaybackState.STATE_PLAYING ||
                    state == PlaybackState.STATE_PAUSED ||
                    state == PlaybackState.STATE_BUFFERING
            } ?: sessions.firstOrNull()
            MediaBridge.attach(active)
        } catch (e: SecurityException) {
            DebugLog.w(TAG, "没有通知使用权: ${e.message}")
        } catch (e: Exception) {
            DebugLog.w(TAG, "枚举媒体会话失败: ${e.message}")
        }
    }
}
