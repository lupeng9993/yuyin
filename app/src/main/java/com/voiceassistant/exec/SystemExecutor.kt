package com.voiceassistant.exec

import android.app.ActivityManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.KeyEvent
import com.voiceassistant.data.AppSettings
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.VaAction
import com.voiceassistant.reminder.Reminder
import com.voiceassistant.reminder.ReminderScheduler
import com.voiceassistant.reminder.ReminderStore
import com.voiceassistant.util.TimeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.Calendar

/**
 * 系统与应用控制执行器：音量、截屏、手电筒、时间日期、天气、提醒、打开应用、媒体播放。
 */
class SystemExecutor(
    private val context: Context,
    private val settings: AppSettings,
    /** 危险动作前的语音确认（退出导航等） */
    private val confirm: suspend (String) -> Boolean = { true }
) {

    private companion object {
        const val TAG = "SystemExec"
    }

    sealed interface Command {
        data object VolumeUp : Command
        data object VolumeDown : Command
        data object VolumeMax : Command
        data object VolumeMin : Command
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
        data object GoHome : Command
        /** 用 Shizuku 执行一条 shell 命令并写日志（验证能力用） */
        data object ShellDump : Command
        /** 截图 + OCR，把屏幕上的文字和坐标写日志 */
        data object OcrScreen : Command
        /** 打开坐标校准页：用户点一下搜索图标，记录坐标 */
        data object CalibrateSearch : Command
        /** 朗读当前聊天里的消息（实验） */
        data object ReadChat : Command
        /** 上传日志到设置里填的地址 */
        data object UploadLog : Command
        /** 取消静音并恢复音量 */
        data object Unmute : Command
        /** 把当前界面的无障碍节点树写进日志（适配不同车机/ROM 用） */
        data object DumpTree : Command
        data object ExitNavigation : Command
        /** slot: 音乐 / 电影 / 倒车 */
        data class Trigger(val slot: String) : Command
        data object TorchOn : Command
        data object TorchOff : Command
        data object ClockNow : Command
        data object DateNow : Command
        data object WeekdayNow : Command
        data class Weather(val dayIndex: Int) : Command
        data class SetReminder(val at: Long, val label: String, val text: String) : Command
        data object CancelReminders : Command
        data object ListReminders : Command
        data object Unsupported : Command
    }

    suspend fun execute(action: VaAction): ActionResult = withContext(Dispatchers.Main) {
        val text = action.rawText.ifBlank { "" }
        val command = parse(text)
        DebugLog.d(TAG, "指令「$text」→ $command")

        when (command) {
            is Command.VolumeUp -> setVolumeAdjust(AudioManager.ADJUST_RAISE, action, "音量已调高")
            is Command.VolumeDown -> setVolumeAdjust(AudioManager.ADJUST_LOWER, action, "音量已调低")
            is Command.VolumeMax -> setVolumePercent(action, 100, "音量已调到最大")
            is Command.VolumeMin -> setVolumePercent(action, 0, "音量已调到最小")
            is Command.TorchOn -> setTorch(action, true)
            is Command.TorchOff -> setTorch(action, false)
            is Command.ClockNow -> clockNow(action)
            is Command.DateNow -> dateNow(action)
            is Command.WeekdayNow -> weekdayNow(action)
            is Command.Weather -> weather(action, command.dayIndex)
            is Command.SetReminder -> setReminder(action, command)
            is Command.CancelReminders -> cancelReminders(action)
            is Command.ListReminders -> listReminders(action)
            is Command.Mute -> {
                val am = audio()
                val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
                DebugLog.d(TAG, "STREAM_MUSIC $before → 静音")
                ActionResult(action, true, "已静音", "mute STREAM_MUSIC")
            }
            is Command.VolumeSet -> setVolumePercent(action, command.percent, "音量已调到${command.percent}%")
            is Command.Screenshot -> takeScreenshot(action)
            is Command.OpenApp -> openApp(action, command.query)
            is Command.CloseApp -> closeApp(action, command.query)
            is Command.PlaySong -> playSong(action, command.query)
            is Command.ShopSearch -> shopSearch(action, command.app, command.keyword)
            is Command.PlayPause -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "已切换播放状态")
            is Command.GoHome -> goHome(action)
            is Command.ShellDump -> {
                // 必须放到 IO 线程：exec 内部会阻塞等待 Shizuku 连接，
                // 在主线程里等会把主线程堵死，绑定回调永远轮不到执行
                val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.voiceassistant.shell.ShizukuShell.exec(
                        "uiautomator dump /sdcard/va_ui.xml >/dev/null 2>&1; cat /sdcard/va_ui.xml",
                        context
                    )
                }
                DebugLog.d("ShellDump", "===== shell 输出开始（${out.length} 字符）=====")
                out.chunked(900).forEach { DebugLog.d("ShellDump", it) }
                DebugLog.d("ShellDump", "===== shell 输出结束 =====")
                val ok = out.isNotBlank() && !out.startsWith("ERR")
                ActionResult(
                    action,
                    ok,
                    if (ok) "命令已执行，输出已写进日志" else "命令没执行成功：${out.take(80)}",
                    "len=${out.length}"
                )
            }
            is Command.CalibrateSearch -> {
                try {
                    context.startActivity(
                        Intent(context, com.voiceassistant.ui.TapCaptureActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    ActionResult(action, true, "请在屏幕上点一下搜索图标的位置", "calib opened")
                } catch (e: Exception) {
                    DebugLog.w(TAG, "打开校准页失败: ${e.message}")
                    ActionResult(action, false, "打不开校准页", e.message.orEmpty())
                }
            }
            is Command.ReadChat -> {
                val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ChatReader(context).readAloud()
                }
                ActionResult(action, true, msg, "readChat")
            }
            is Command.OcrScreen -> {                val count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.voiceassistant.shell.OcrHelper.captureAndLog(context)
                }
                val ok = count > 0
                ActionResult(
                    action,
                    ok,
                    if (ok) "识别到 $count 行文字，已写进日志" else "没识别到文字（截图失败或界面是空白）",
                    "lines=$count"
                )
            }
            is Command.UploadLog -> {                val msg = com.voiceassistant.log.LogUploader.upload(context, settings.logUploadUrl)
                ActionResult(action, msg.startsWith("日志已上传"), msg, msg)
            }
            is Command.Unmute -> {
                val am = audio()
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (max * 0.3f).toInt().coerceIn(1, max)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                DebugLog.d(TAG, "取消静音 → $target/$max")
                ActionResult(action, true, "已取消静音，音量调到30%", "unmute → $target/$max")
            }
            is Command.DumpTree -> {
                A11yBridge.dumpTree("NodeDump")
                ActionResult(action, true, "已经把当前界面结构导出到日志了", "dumpTree done")
            }
            is Command.ExitNavigation -> exitNavigation(action)
            is Command.Trigger -> trigger(action, command.slot)
            is Command.Next -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_NEXT, "已切到下一首")
            is Command.Previous -> mediaKey(action, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "已切到上一首")
            is Command.Unsupported -> ActionResult(action, false, "这个操作我还不支持", "未匹配到系统指令")
        }
    }

    private fun audio(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 系统流调不动时的兜底：先模拟音量键，再让播放器自己调 */
    private fun fallbackVolume(up: Boolean, before: Int): String {
        val am = audio()
        val key = if (up) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        repeat(2) {
            try {
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            } catch (e: Exception) {
                // ignore
            }
        }
        android.os.SystemClock.sleep(120)
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != before) {
            return "音量键生效 → ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}"
        }
        if (com.voiceassistant.media.MediaBridge.adjustVolume(up)) {
            return "已让播放器自己调音量"
        }
        // 最后一级：弹出透明界面（系统只允许"前台应用"改音量）
        val burst = runCatching {
            context.startActivity(
                Intent(context, com.voiceassistant.ui.VolumeBurstActivity::class.java)
                    .putExtra("direction", if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            true
        }.getOrDefault(false)
        if (burst) {
            android.os.SystemClock.sleep(600)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != before) {
                return "已通过前台界面调整 → ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}"
            }
            return "已尝试前台调整，音量仍被系统锁住"
        }
        return "系统与播放器都不让调（本机可能由硬件控制音量）"
    }

    private fun setVolumeAdjust(direction: Int, action: VaAction, speech: String): ActionResult {
        val am = audio()
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // 显式指定 STREAM_MUSIC：音乐/视频/游戏都在这条流上。
        // 之前用「建议流」会按当前活跃音频选流，我们自己在播报时就会调到 assistant 流，
        // 表现就是"只在语音助手内有用，别的 App 没反应"。
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        var after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        var detail = "STREAM_MUSIC $before → $after / $max (volumeFixed=${am.isVolumeFixed})"

        if (after == before) {
            // 系统不许改 → 试音量键，再试播放器
            detail += "｜" + fallbackVolume(direction == AudioManager.ADJUST_RAISE, before)
            after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        DebugLog.d(TAG, detail)
        return ActionResult(action, true, speech, detail)
    }

    private fun mediaKey(action: VaAction, keyCode: Int, speech: String): ActionResult {
        // 优先走 MediaController（需要「通知使用权」），它才真的能控制其它播放器
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> com.voiceassistant.media.MediaBridge.playPause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> com.voiceassistant.media.MediaBridge.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> com.voiceassistant.media.MediaBridge.previous()
            else -> false
        }
        if (handled) {
            return ActionResult(action, true, speech, "MediaController key=$keyCode")
        }

        return try {
            val am = audio()
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            val hint = if (com.voiceassistant.media.MediaBridge.hasController()) "" else "（控制播放需要在设置里开「通知使用权」）"
            ActionResult(action, true, speech + hint, "dispatchMediaKeyEvent key=$keyCode")
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

            // 第 0 步：有 Shizuku 就直接 force-stop，这才是真正能停掉应用的方式
            if (ShellExecutor.granted() && ShellExecutor.forceStop(pkg)) {
                DebugLog.d(TAG, "Shizuku force-stop 成功 $pkg")
                return ActionResult(action, true, "已关闭$label", "shizuku am force-stop pkg=$pkg")
            }

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

            // 实在关不掉：至少退回桌面，别把用户卡在别人的应用里
            val wentHome = A11yBridge.isReady() && A11yBridge.home()
            val speech = if (wentHome) {
                "关不掉$label，已经帮你回到桌面"
            } else {
                "没能关闭$label，系统不允许关闭正在使用的应用"
            }
            ActionResult(action, false, speech, "kill 与 force-stop 均失败，home=$wentHome pkg=$pkg")
        } catch (e: Exception) {
            DebugLog.e(TAG, "关闭应用失败", e)
            ActionResult(action, false, "关闭「$query」失败", e.message.orEmpty())
        }
    }

    private fun goHome(action: VaAction): ActionResult {
        if (!A11yBridge.isReady()) {
            return ActionResult(action, false, "回桌面需要先开启无障碍服务", "A11yBridge 不可用")
        }
        return if (A11yBridge.home()) {
            ActionResult(action, true, "已回到桌面", "GLOBAL_ACTION_HOME")
        } else {
            ActionResult(action, false, "没能回到桌面", "performGlobalAction 返回 false")
        }
    }

    private fun setVolumePercent(action: VaAction, percent: Int, speech: String): ActionResult {
        val am = audio()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100f).toInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        DebugLog.d(TAG, "STREAM_MUSIC $before → $after (目标 $target / max=$max)")
        return ActionResult(action, true, speech, "level $before→$after/$max")
    }

    /** 手电筒：找有闪光灯的摄像头，用 CameraManager 直接开关 */
    private fun setTorch(action: VaAction, on: Boolean): ActionResult {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cam ->
                cm.getCameraCharacteristics(cam)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionResult(action, false, "这台设备没有闪光灯", "no flash unit")

            cm.setTorchMode(id, on)
            ActionResult(action, true, if (on) "手电筒已打开" else "手电筒已关闭", "camera=$id")
        } catch (e: SecurityException) {
            ActionResult(action, false, "需要相机权限才能控制手电筒", "SecurityException")
        } catch (e: Exception) {
            DebugLog.e(TAG, "手电筒失败", e)
            ActionResult(action, false, "手电筒打不开，可能相机被占用", e.message.orEmpty())
        }
    }

    private fun clockNow(action: VaAction): ActionResult {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val speech = if (m == 0) "现在${h}点" else "现在${h}点${m}分"
        return ActionResult(action, true, speech, "clock=$h:$m")
    }

    private fun dateNow(action: VaAction): ActionResult {
        val cal = Calendar.getInstance()
        val speech = "今天是${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日，${weekdayName(cal)}"
        return ActionResult(action, true, speech, "date")
    }

    private fun weekdayNow(action: VaAction): ActionResult =
        ActionResult(action, true, "今天是${weekdayName(Calendar.getInstance())}", "weekday")

    private fun weekdayName(cal: Calendar): String = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "星期一"
        Calendar.TUESDAY -> "星期二"
        Calendar.WEDNESDAY -> "星期三"
        Calendar.THURSDAY -> "星期四"
        Calendar.FRIDAY -> "星期五"
        Calendar.SATURDAY -> "星期六"
        else -> "星期日"
    }

    /**
     * 天气播报：
     * - 定位城市与"家"同城 → 只说一次
     * - 不同城 → 先报定位城市，再报"您家X"
     */
    private suspend fun weather(action: VaAction, dayIndex: Int): ActionResult {
        if (!settings.hasAmapKey) {
            return ActionResult(action, false, "还没有配置高德 Key，查不了天气", "amapKey 为空")
        }
        val service = com.voiceassistant.weather.WeatherService(context, settings)
        val homeName = settings.homeCity.trim().ifEmpty { "泰安" }
        val home = service.cityByKeyword(homeName)
            ?: return ActionResult(action, false, "没查到「$homeName」这个城市", "district 查询失败")

        val located = service.currentCity()
        val locatedCity = located ?: home
        val homeDays = service.forecast(home)
            ?: return ActionResult(action, false, "天气服务没返回数据，稍后再试", "forecast 为空")
        val day = homeDays.getOrNull(dayIndex) ?: homeDays.last()

        val dayWord = when (dayIndex) {
            1 -> "明天"
            2 -> "后天"
            else -> "今天"
        }
        val homeShort = home.name.removeSuffix("市")
        val homeSpeech = "$homeShort$dayWord${day.weatherText()}，${day.low} 到 ${day.high} 度"

        val sameCity = locatedCity.name.removeSuffix("市") == homeShort
        val speech = if (sameCity || located == null) {
            homeSpeech
        } else {
            val locatedShort = locatedCity.name.removeSuffix("市")
            val locatedDay = service.forecast(locatedCity)?.let { list ->
                list.getOrNull(dayIndex) ?: list.lastOrNull()
            }
            val locatedSpeech = if (locatedDay != null) {
                "当前城市为$locatedShort，$locatedShort$dayWord${locatedDay.weatherText()}，${locatedDay.low} 到 ${locatedDay.high} 度。"
            } else {
                "当前城市为$locatedShort。"
            }
            locatedSpeech + "您家$homeSpeech"
        }
        return ActionResult(
            action, true, speech,
            "home=$homeShort/${home.adcode} located=${locatedCity.name} day=$dayIndex"
        )
    }

    private fun setReminder(action: VaAction, command: Command.SetReminder): ActionResult {
        if (command.at <= System.currentTimeMillis()) {
            return ActionResult(
                action, false,
                "没听清提醒时间，请再说一次，比如「8点提醒我开会」",
                "时间未解析：${action.rawText}"
            )
        }
        val reminder = Reminder(
            id = ReminderStore.nextId(context),
            triggerAt = command.at,
            text = command.text,
            label = command.label
        )
        val exact = ReminderScheduler.add(context, reminder)
        val speech = buildString {
            append("好的，")
            append(reminder.label)
            append("提醒你")
            append(if (reminder.text == "提醒") "" else reminder.text)
            if (!exact) append("。系统没给精确闹钟权限，可能会晚几分钟")
        }
        return ActionResult(action, true, speech, "id=${reminder.id} at=${reminder.triggerAt} exact=$exact")
    }

    private fun cancelReminders(action: VaAction): ActionResult {
        val count = ReminderStore.load(context).size
        ReminderScheduler.cancelAll(context)
        val speech = if (count == 0) "现在没有提醒" else "已取消 $count 个提醒"
        return ActionResult(action, true, speech, "cancelled=$count")
    }

    private fun listReminders(action: VaAction): ActionResult {
        val list = ReminderScheduler.pending(context)
        if (list.isEmpty()) return ActionResult(action, true, "现在没有待提醒的事", "empty")
        val preview = list.take(3).joinToString("；") { "${it.label} ${it.text}" }
        val speech = if (list.size <= 3) {
            "还有 ${list.size} 个提醒：$preview"
        } else {
            "还有 ${list.size} 个提醒，最近的是：$preview"
        }
        return ActionResult(action, true, speech, "count=${list.size}")
    }

    /** 退出当前导航：确认后连按两次返回（导航 → 概览 → 主界面），**不关闭高德** */
    private suspend fun exitNavigation(action: VaAction): ActionResult {
        if (!A11yBridge.isReady()) {
            return ActionResult(action, false, "退出导航需要先开启无障碍服务", "a11y off")
        }
        if (!confirm("确认退出当前导航吗？")) {
            return ActionResult(action, false, "已取消", "用户取消退出导航")
        }
        val first = A11yBridge.back()
        delay(700)
        val second = A11yBridge.back()
        delay(400)
        return if (first || second) {
            ActionResult(action, true, "已退出当前导航", "back x2 ($first/$second)")
        } else {
            ActionResult(action, false, "没能退出导航", "performGlobalAction(BACK) 失败")
        }
    }

    /** 音乐 / 电影 / 倒车：启动设置里绑定的 App；音乐还会自动开始播放 */
    private suspend fun trigger(action: VaAction, slot: String): ActionResult {
        val pkg = when (slot) {
            "音乐" -> settings.triggerMusic
            "电影" -> settings.triggerMovie
            else -> settings.triggerReverse
        }
        if (pkg.isBlank()) {
            openSettingsPage()
            return ActionResult(
                action, false,
                "还没设置「$slot」对应哪个应用，已经帮你打开设置页",
                "unbound slot=$slot"
            )
        }
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ActionResult(action, false, "「$slot」绑定的应用没装", "launchIntent null pkg=$pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            val label = AppLauncher.labelOf(context, pkg) ?: slot
            if (slot == "音乐") {
                // 等播放器起来，再主动发一次「播放」
                delay(1600)
                var played = com.voiceassistant.media.MediaBridge.play()
                if (!played) {
                    try {
                        audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        audio().dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                        played = true
                    } catch (e: Exception) {
                        DebugLog.w(TAG, "自动播放失败: ${e.message}")
                    }
                }
                DebugLog.d(TAG, "已尝试开始播放 played=$played")
            }
            ActionResult(action, true, "已打开$label", "pkg=$pkg")
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动 $slot 失败", e)
            ActionResult(action, false, "打不开「$slot」绑定的应用", e.message.orEmpty())
        }
    }

    private fun openSettingsPage() {
        try {
            context.startActivity(
                Intent(context, com.voiceassistant.ui.MainActivity::class.java)
                    .putExtra("screen", "settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            DebugLog.w(TAG, "打开设置页失败: ${e.message}")
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

        // 回到桌面
        if (Regex("^(?:回到|返回|显示|打开|去)?(?:桌面|主页|主屏幕|首页|home)$", RegexOption.IGNORE_CASE).matches(t) ||
            t == "桌面" || t == "回桌面"
        ) {
            return Command.GoHome
        }

        // 提醒（必须在「打开应用」之前，避免「提醒我打开空调」被当成打开应用）
        if (t.contains("提醒") || t.contains("叫我")) {
            if (t.contains("取消")) return Command.CancelReminders
            if (t.contains("几个") || t.contains("多少") || t.contains("还有")) return Command.ListReminders
            val parsed = TimeParser.parse(t)
            if (parsed != null) {
                val event = t.substringAfter("提醒我", "")
                    .ifBlank { t.substringAfter("提醒", "") }
                    .ifBlank { t.substringAfter("叫我", "") }
                    .trim()
                    .trimEnd('。', '，', '！', '!')
                    .ifBlank { "提醒" }
                return Command.SetReminder(parsed.at, parsed.label, event)
            }
            // 有"提醒"但没解析出时间：交给上层提示补一句
            return Command.SetReminder(0L, "", "")
        }

        // 时间 / 日期 / 星期
        if (Regex("^(?:现在)?(?:几点了?|什么时间|现在时间|报个时间)$").matches(t)) return Command.ClockNow
        if (t.contains("星期几") || t.contains("周几") || t.contains("礼拜几")) return Command.WeekdayNow
        if (t.contains("几号") || t.contains("日期") || t.contains("今天是几")) return Command.DateNow

        // 天气
        if (t.contains("天气")) {
            val idx = when {
                t.contains("后天") -> 2
                t.contains("明天") -> 1
                else -> 0
            }
            return Command.Weather(idx)
        }

        // 手电筒（必须在「打开应用」之前，否则会去找叫"手电筒"的 App）
        if (t.contains("手电筒")) {
            return if (t.contains("关") || t.contains("灭")) Command.TorchOff else Command.TorchOn
        }

        // 音量
        if (t.contains("静音")) return Command.Mute
        if (t.contains("音量") || t.contains("声音")) {
            if (t.contains("最大") || t.contains("最响") || t.contains("满")) return Command.VolumeMax
            if (t.contains("最小") || t.contains("最低")) return Command.VolumeMin
            val percent = extractPercent(t)
            if (percent != null) return Command.VolumeSet(percent)
            if (t.contains("调高") || t.contains("调大") || t.contains("大一点") ||
                t.contains("高一点") || t.contains("大点声") || t.contains("提高")
            ) return Command.VolumeUp
            if (t.contains("调低") || t.contains("调小") || t.contains("小一点") ||
                t.contains("低一点") || t.contains("小点声") || t.contains("降低")
            ) return Command.VolumeDown
        }

        // Shizuku 能力验证：说「执行命令」跑一次 uiautomator dump
        // 朗读当前聊天消息（实验）
        if (t.contains("朗读消息") || t.contains("读消息") || t.contains("念消息")) {
            return Command.ReadChat
        }

        // 校准搜索图标位置
        if (t.contains("校准") || t.contains("记录位置") || t.contains("记录搜索位置")) {
            return Command.CalibrateSearch
        }

        // 截图 + OCR 验证（看能不能认出微信界面的文字）
        if (t.contains("识别屏幕") || t.contains("截图识别") || t.contains("识别界面")) {
            return Command.OcrScreen
        }

        if (t.contains("执行命令")) return Command.ShellDump

        // 导出节点树（调试用，说一句就在当前界面导出，不会离开目标 App）
        // 语音会把「树」识别成「数」，所以按「节点」判断，并兼容「导出结构」
        if (t.contains("节点") || t.contains("导出结构") || t.contains("导出界面结构")) {
            return Command.DumpTree
        }

        // 取消静音（静音后助手哑了，靠这条指令恢复）
        if (t in setOf("取消静音", "解除静音", "恢复音量", "打开声音", "恢复声音")) {
            return Command.Unmute
        }

        // 上传日志（调试用）
        if (t.contains("上传日志") || t == "传日志") return Command.UploadLog

        // 触发卡片：音乐 / 电影 / 倒车 + 各种说法
        when (t) {
            "音乐", "放音乐", "听音乐", "来点音乐", "听歌", "来首歌", "打开音乐" -> return Command.Trigger("音乐")
            "电影", "看电影", "看个电影", "来点电影", "打开电影" -> return Command.Trigger("电影")
            "倒车", "倒车影像", "倒车模式", "倒车画面", "打开倒车" -> return Command.Trigger("倒车")
        }

        // 退出导航：只认"退出/结束/停止"；「关闭导航」走下面的关闭应用（真关高德）
        if (Regex("^(?:退出|结束|停止)(?:当前|正在|这个)?导航$").matches(t)) {
            return Command.ExitNavigation
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
