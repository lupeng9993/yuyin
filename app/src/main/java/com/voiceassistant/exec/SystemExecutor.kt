package com.voiceassistant.exec

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.VaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        data class CloseApp(val query: String) : Command
        data class PlaySong(val query: String) : Command
        data class ShopSearch(val app: String, val keyword: String) : Command
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
                try {
                    @Suppress("DEPRECATION")
                    am.adjustSuggestedStreamVolume(
                        AudioManager.ADJUST_MUTE,
                        AudioManager.USE_DEFAULT_STREAM_TYPE,
                        AudioManager.FLAG_SHOW_UI
                    )
                } catch (e: Exception) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                }
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
            is Command.CloseApp -> closeApp(action, command.query)
            is Command.PlaySong -> playSong(action, command.query)
            is Command.ShopSearch -> shopSearch(action, command.app, command.keyword)
            is Command.PlayPause -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "已切换播放状态")
            is Command.Next -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_NEXT, "已切到下一首")
            is Command.Previous -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "已切到上一首")
            is Command.Unsupported -> ActionResult(action, false, "这个操作我还不支持", "未匹配到系统指令")
        }
    }

    private fun audio(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun setVolumeAdjust(direction: Int, action: VaAction, speech: String): ActionResult {
        val am = audio()
        // 用「建议流」：和音量键一样作用于当前正在出声的那个流，
        // 这样在别的 App 里播着音乐时也能真的调它，而不是只影响本应用
        try {
            @Suppress("DEPRECATION")
            am.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
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
        if (pkg == null) {
            val near = AppLauncher.suggestions(context, query)
            val speech = if (near.isEmpty()) {
                "没找到「$query」这个应用"
            } else {
                "没找到「$query」，是想打开${near.joinToString("、")}吗"
            }
            return ActionResult(action, false, speech, "未匹配到包名，候选=$near")
        }
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

    private suspend fun closeApp(action: VaAction, query: String): ActionResult {
        val pkg = AppLauncher.resolve(context, query)
        if (pkg == null) {
            val near = AppLauncher.suggestions(context, query)
            val speech = if (near.isEmpty()) "没找到「$query」这个应用"
            else "没找到「$query」，是想关闭${near.joinToString("、")}吗"
            return ActionResult(action, false, speech, "未匹配到包名，候选=$near")
        }

        val label = AppLauncher.labelOf(context, pkg) ?: query

        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // 第一步：先回桌面。前台应用系统不允许杀，必须让它先变成后台
            if (A11yBridge.isReady()) {
                A11yBridge.home()
                awaitNotForeground(pkg, 1500)
            }

            // 第二步：杀它的后台进程
            am.killBackgroundProcesses(pkg)
            delay(600)

            if (!isForeground(pkg)) {
                DebugLog.d(TAG, "已关闭 $pkg")
                return ActionResult(action, true, "已关闭$label", "killBackgroundProcesses pkg=$pkg")
            }

            // 第三步：还是前台（或压根没杀掉）→ 走系统设置页的「强行停止」
            if (A11yBridge.isReady() && forceStopViaSettings(pkg)) {
                DebugLog.d(TAG, "通过设置页强行停止 $pkg")
                return ActionResult(action, true, "已强制停止$label", "force-stop via settings pkg=$pkg")
            }

            // 都失败就老实说
            val hint = if (A11yBridge.isReady()) "" else "（开启无障碍可以走设置页强制停止）"
            ActionResult(
                action, false,
                "没能关闭$label，系统不允许关闭正在使用的应用$hint",
                "kill 与 force-stop 均失败，pkg=$pkg"
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭应用失败", e)
            ActionResult(action, false, "关闭「$query」失败", e.message.orEmpty())
        }
    }

    private fun isForeground(pkg: String): Boolean = A11yBridge.currentWindowPackage() == pkg

    /** 等目标应用彻底退到后台（最多 timeoutMs） */
    private suspend fun awaitNotForeground(pkg: String, timeoutMs: Long) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!isForeground(pkg)) return
            delay(150)
        }
    }

    private suspend fun awaitNode(timeoutMs: Long, finder: () -> android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            finder()?.let { return it }
            delay(200)
        }
        return null
    }

    /**
     * 打开「应用详情」页点「强行停止」——这是普通应用唯一能真正停掉别人的正规途径。
     * 需要无障碍服务。
     */
    private suspend fun forceStopViaSettings(pkg: String): Boolean {
        return try {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            delay(900)

            val stop = awaitNode(3000) {
                A11yBridge.findNode(A11yBridge.TextFilter(exact = "强行停止"))
                    ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "强制停止"))
                    ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "强行停止"))
            } ?: return false

            if (!A11yBridge.click(stop)) return false
            delay(700)

            val confirm = awaitNode(2500) {
                A11yBridge.findNode(A11yBridge.TextFilter(exact = "确定"))
                    ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "OK"))
                    ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "强行停止"))
            }
            confirm?.let { A11yBridge.click(it) }
            delay(700)
            A11yBridge.home()
            true
        } catch (e: Exception) {
            DebugLog.w(TAG, "设置页强行停止失败: ${e.message}")
            false
        }
    }

    /** 「来一首晴天」：走系统媒体搜索 Intent，多数播放器都支持 */
    private fun playSong(action: VaAction, query: String): ActionResult {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val resolved = context.packageManager.resolveActivity(intent, 0)
            if (resolved != null) {
                context.startActivity(intent)
                ActionResult(
                    action, true, "好的，播放$query",
                    "PLAY_FROM_SEARCH → ${resolved.activityInfo.packageName}"
                )
            } else {
                val pkg = AppLauncher.resolve(context, "音乐") ?: AppLauncher.resolve(context, "网易云")
                if (pkg != null) {
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    }
                    ActionResult(
                        action, false,
                        "没有播放器支持按歌名搜索，已帮你打开${AppLauncher.labelOf(context, pkg) ?: "音乐"}",
                        "无 MEDIA_PLAY_FROM_SEARCH 接收方"
                    )
                } else {
                    ActionResult(action, false, "没找到能播放音乐的 App", "无接收方且无音乐应用")
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "播放歌曲失败", e)
            ActionResult(action, false, "播放失败", e.message.orEmpty())
        }
    }

    /**
     * 「在淘宝搜蓝牙耳机」：打开对应 App 并把关键词放进剪贴板。
     * 明确不做下单/支付——那需要支付密码和平台风控，自动化既做不到也不该做。
     */
    private fun shopSearch(action: VaAction, appName: String, keyword: String): ActionResult {
        val pkg = AppLauncher.resolve(context, appName)
            ?: return ActionResult(action, false, "手机里没找到「$appName」", "包名未匹配")

        copyToClipboard(keyword)

        val encoded = Uri.encode(keyword)
        val uri = when (appName) {
            "淘宝" -> "taobao://s.taobao.com/search?q=$encoded"
            "闲鱼" -> "fleamarket://search?keyword=$encoded"
            "美团" -> "imeituan://www.meituan.com/search?q=$encoded"
            else -> null
        }
        if (uri != null) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return ActionResult(
                    action, true, "已打开$appName，搜索「$keyword」。下单要你自己确认支付",
                    "uri=$uri"
                )
            } catch (e: Exception) {
                DebugLog.w(TAG, "搜索链接打不开，退回启动应用: ${e.message}")
            }
        }

        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult(action, false, "$appName 打不开", "无启动入口")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            ActionResult(
                action, true,
                "已打开$appName，搜索词「$keyword」已复制，粘贴就能搜。下单我不代做，要你自己确认支付",
                "pkg=$pkg keyword=$keyword"
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "打开购物应用失败", e)
            ActionResult(action, false, "打开${appName}失败", e.message.orEmpty())
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voiceassistant", text))
        } catch (e: Exception) {
            DebugLog.w(TAG, "复制到剪贴板失败: ${e.message}")
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

        // 关闭应用
        val closeMatch = Regex("^(?:关闭|关掉|退出|杀掉|结束|关)(.+)$").find(t)
        if (closeMatch != null) {
            val target = cleanTarget(closeMatch.groupValues[1])
            if (target.isNotEmpty()) return Command.CloseApp(target)
        }

        // 购物类：在淘宝/拼多多/美团…搜索或买东西
        val shopApps = listOf("淘宝", "拼多多", "美团", "京东", "闲鱼", "天猫")
        val shopApp = shopApps.firstOrNull { t.contains(it) }
        if (shopApp != null) {
            val keyword = t
                .replace(shopApp, "")
                .replace(Regex("^(?:在|打开|请|帮我|给我|用)"), "")
                .replace(Regex("(?:上|里)?(?:帮我|给我)?"), "")
                .replace(Regex("(?:搜索|搜一下|搜|找一下|找|购买|买点|买)"), "")
                .replace(Regex("[吧呀啊]$"), "")
                .trim()
            if (keyword.isNotEmpty()) return Command.ShopSearch(shopApp, keyword)
        }

        // 播放指定歌曲：「来一首晴天」「播放晴天」「放晴天」
        Regex("^(?:播放|放|来一首|来首|听一下|听)(.+)$").find(t)?.let { m ->
            val song = m.groupValues[1].replace(Regex("[吧呀啊]$"), "").trim()
            if (song.isNotEmpty() && song !in listOf("音乐", "歌", "歌曲", "视频", "声音")) {
                return Command.PlaySong(song)
            }
        }

        // 打开应用
        val openMatch = Regex("^(?:打开|开启|启动|运行)(.+)$").find(t)
        if (openMatch != null) {
            val target = cleanTarget(openMatch.groupValues[1])
            if (target.isNotEmpty()) return Command.OpenApp(target)
        }

        // 媒体键（放在「播放歌名」之后，避免把歌名吃成暂停/播放）
        if (t.contains("暂停") || t.contains("继续播放") || t.contains("播放")) return Command.PlayPause
        if (t.contains("下一首") || t.contains("切歌")) return Command.Next
        if (t.contains("上一首")) return Command.Previous

        return Command.Unsupported
    }

    private fun cleanTarget(raw: String): String = raw
        .removeSuffix("吧")
        .removeSuffix("一下")
        .removeSuffix("应用")
        .removeSuffix("软件")
        .trim()

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
