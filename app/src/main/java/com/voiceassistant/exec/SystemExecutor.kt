package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.view.KeyEvent
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.VaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 系统与应用控制执行器：音量、截屏、打开应用、媒体播放控制。
 *
 * 只实现文档列出的能力，不做扩展（不做蓝牙、不做智能家居、不猜测用户意图）。
 */
class SystemExecutor(private val context: Context) {

    private companion object {
        const val TAG = "SystemExec"
    }

    sealed interface Command {
        data object VolumeUp : Command
        data object VolumeDown : Command
        data class VolumeSet(val percent: Int) : Command
        data object Mute : Command
        data object Screenshot : Command
        data class OpenApp(val query: String) : Command
        data object PlayPause : Command
        data object Next : Command
        data object Previous : Command
        data object Unsupported : Command
    }

    suspend fun execute(action: VaAction): ActionResult = withContext(Dispatchers.Main) {
        val text = action.rawText.ifBlank { "" }
        val command = parse(text)
        DebugLog.d(TAG, "指令「$text」→ $command")

        when (command) {
            is Command.VolumeUp -> setVolumeAdjust(AudioManager.ADJUST_RAISE, action, "音量已调高")
            is Command.VolumeDown -> setVolumeAdjust(AudioManager.ADJUST_LOWER, action, "音量已调低")
            is Command.Mute -> {
                val am = audio()
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                ActionResult(action, true, "已静音", "ADJUST_MUTE")
            }
            is Command.VolumeSet -> {
                val am = audio()
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (max * command.percent / 100f).toInt().coerceIn(0, max)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                ActionResult(action, true, "音量已调到${command.percent}%", "level=$target/$max")
            }
            is Command.Screenshot -> takeScreenshot(action)
            is Command.OpenApp -> openApp(action, command.query)
            is Command.PlayPause -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "已切换播放状态")
            is Command.Next -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_NEXT, "已切到下一首")
            is Command.Previous -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "已切到上一首")
            is Command.Unsupported -> ActionResult(action, false, "这个操作我还不支持", "未匹配到系统指令")
        }
    }

    private fun audio(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun setVolumeAdjust(direction: Int, action: VaAction, speech: String): ActionResult {
        audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        return ActionResult(action, true, speech, "ADJUST_$direction")
    }

    private fun mediaKey(action: VaAction, keyCode: Int, speech: String): ActionResult {
        return try {
            val am = audio()
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ActionResult(action, true, speech, "key=$keyCode")
        } catch (e: Exception) {
            DebugLog.e(TAG, "媒体按键失败", e)
            ActionResult(action, false, "没能控制播放", "dispatchMediaKeyEvent 失败")
        }
    }

    private fun openApp(action: VaAction, query: String): ActionResult {
        val pkg = AppLauncher.resolve(context, query)
            ?: return ActionResult(action, false, "没找到「$query」这个应用", "包名未匹配")
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult(action, false, "「$query」打不开", "无启动入口")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult(action, true, "已打开${AppLauncher.labelOf(context, pkg) ?: query}", "pkg=$pkg")
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动应用失败", e)
            ActionResult(action, false, "打开「$query」失败", e.message.orEmpty())
        }
    }

    private suspend fun takeScreenshot(action: VaAction): ActionResult {
        if (!A11yBridge.isReady()) {
            return ActionResult(action, false, "截屏需要先开启无障碍服务", "A11yBridge 未就绪")
        }
        if (!A11yBridge.canTakeScreenshot()) {
            return ActionResult(action, false, "当前系统版本不支持直接截屏", "需要 Android 11 及以上")
        }
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            A11yBridge.takeScreenshot { bmp -> if (cont.isActive) cont.resume(bmp) }
        } ?: return ActionResult(action, false, "截屏失败", "takeScreenshot 返回 null")

        val saved = ScreenshotSaver.save(context, bitmap)
        return if (saved != null) {
            ActionResult(action, true, "已截图保存", saved)
        } else {
            ActionResult(action, false, "截图保存失败", "MediaStore 写入失败")
        }
    }

    // ---------------- 指令解析 ----------------

    fun parse(rawText: String): Command {
        val t = rawText.replace(" ", "")

        // 截屏
        if (t.contains("截屏") || t.contains("截图") || t.contains("屏幕截图")) return Command.Screenshot

        // 音量
        if (t.contains("静音")) return Command.Mute
        if (t.contains("音量") || t.contains("声音")) {
            val percent = extractPercent(t)
            if (percent != null) return Command.VolumeSet(percent)
            if (t.contains("调高") || t.contains("调大") || t.contains("大一点") ||
                t.contains("高一点") || t.contains("大点声") || t.contains("提高")
            ) return Command.VolumeUp
            if (t.contains("调低") || t.contains("调小") || t.contains("小一点") ||
                t.contains("低一点") || t.contains("小点声") || t.contains("降低")
            ) return Command.VolumeDown
        }

        // 媒体
        if (t.contains("暂停") || t.contains("继续播放") || t.contains("播放")) return Command.PlayPause
        if (t.contains("下一首") || t.contains("切歌")) return Command.Next
        if (t.contains("上一首")) return Command.Previous

        // 打开应用
        val openMatch = Regex("^(?:打开|开启|启动|运行)(.+)$").find(t)
        if (openMatch != null) {
            val target = openMatch.groupValues[1]
                .removeSuffix("吧").removeSuffix("一下").trim()
            if (target.isNotEmpty()) return Command.OpenApp(target)
        }

        return Command.Unsupported
    }

    /** 支持「调到30%」「调到三十」「音量50」这几种说法 */
    private fun extractPercent(text: String): Int? {
        val digit = Regex("(\\d{1,3})\\s*%?").find(text)
        if (digit != null) {
            val v = digit.groupValues[1].toIntOrNull() ?: return null
            return v.coerceIn(0, 100)
        }
        val cn = Regex("([一二三四五六七八九十百]+)").find(text)
        if (cn != null) {
            val v = chineseNumber(cn.groupValues[1]) ?: return null
            return v.coerceIn(0, 100)
        }
        return null
    }

    private fun chineseNumber(s: String): Int? {
        val digits = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s == "十") return 10
        if (s.contains("百")) return 100
        if (s.startsWith("十")) return 10 + (digits[s.getOrNull(1)] ?: 0)
        val idx = s.indexOf('十')
        if (idx > 0) {
            val tens = digits[s[0]] ?: return null
            val ones = if (s.length > idx + 1) digits[s[idx + 1]] ?: 0 else 0
            return tens * 10 + ones
        }
        return if (s.length == 1) digits[s[0]] else null
    }
}
