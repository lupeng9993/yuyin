package com.voiceassistant.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.voiceassistant.R
import com.voiceassistant.log.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class Reminder(
    val id: Int,
    val triggerAt: Long,
    val text: String,
    /** 播报用的时间说法，例如「明天 07:30」 */
    val label: String
)

/** 提醒持久化：简单存成 JSON，条数很少不需要数据库 */
object ReminderStore {

    private const val PREF = "va_reminders"
    private const val KEY = "list"

    fun load(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Reminder(
                    id = o.optInt("id"),
                    triggerAt = o.optLong("at"),
                    text = o.optString("text"),
                    label = o.optString("label")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("at", it.triggerAt)
                    put("text", it.text)
                    put("label", it.label)
                }
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun nextId(context: Context): Int =
        (load(context).maxOfOrNull { it.id } ?: 0) + 1
}

/** 提醒通知：挂起时显示倒计时，到点显示提醒 */
object ReminderNotifier {

    private const val CH_COUNTDOWN = "va_reminder_countdown"
    private const val CH_ALARM = "va_reminder_alarm"
    private const val BASE_COUNTDOWN_ID = 10_000
    private const val BASE_ALARM_ID = 20_000

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CH_COUNTDOWN) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_COUNTDOWN,
                    "提醒倒计时",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "显示还有多久到提醒时间"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                }
            )
        }
        if (nm.getNotificationChannel(CH_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALARM,
                    "到点提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "提醒到点时弹出并响铃"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
                    setSound(
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableLights(true)
                }
            )
        }
    }

    /** 倒计时用系统 chronometer 自动走，不需要我们每秒刷新 */
    fun showCountdown(context: Context, reminder: Reminder) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(context, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(reminder.text)
            .setContentText("${reminder.label} 提醒你")
            .setWhen(reminder.triggerAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            nm.notify(BASE_COUNTDOWN_ID + reminder.id, n)
        } catch (e: Exception) {
            DebugLog.w("Reminder", "倒计时通知失败: ${e.message}")
        }
    }

    fun cancelCountdown(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(BASE_COUNTDOWN_ID + id)
    }

    fun showAlarm(context: Context, text: String, label: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.voiceassistant.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 全屏提醒页：系统会在锁屏/后台直接把页面拉起来
        val fullScreen = PendingIntent.getActivity(
            context, 1,
            Intent(context, ReminderAlertActivity::class.java)
                .putExtra("text", text)
                .putExtra("label", label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CH_ALARM)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("提醒：$text")
            .setContentText(label)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setFullScreenIntent(fullScreen, true)
            .build()
        try {
            nm.notify(BASE_ALARM_ID + (System.currentTimeMillis() % 1000).toInt(), n)
        } catch (e: Exception) {
            DebugLog.w("Reminder", "提醒通知失败: ${e.message}")
        }
    }
}

/** 调度：到点由 ReminderReceiver 接管 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    private const val REQUEST_CODE_BASE = 5_000

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun add(context: Context, reminder: Reminder): Boolean {
        val list = ReminderStore.load(context).filterNot { it.id == reminder.id } + reminder
        ReminderStore.save(context, list)

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(context, reminder)
        val exact = canScheduleExact(context)
        try {
            if (exact) {
                // setAlarmClock 是最高优先级：Doze / 省电模式都照常准点触发
                val show = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, com.voiceassistant.ui.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(reminder.triggerAt, show), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            }
        } catch (e: SecurityException) {
            DebugLog.w(TAG, "没有精确闹钟权限，降级普通闹钟: ${e.message}")
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAt, pi)
            ReminderNotifier.showCountdown(context, reminder)
            return false
        } catch (e: Exception) {
            DebugLog.e(TAG, "设置提醒失败", e)
            return false
        }

        ReminderNotifier.showCountdown(context, reminder)
        DebugLog.d(TAG, "已设置提醒 #${reminder.id} ${reminder.label} ${reminder.text} exact=$exact")
        return exact
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ReminderStore.load(context).forEach { r ->
            am.cancel(alarmIntent(context, r))
            ReminderNotifier.cancelCountdown(context, r.id)
        }
        ReminderStore.save(context, emptyList())
        DebugLog.d(TAG, "已取消全部提醒")
    }

    fun pending(context: Context): List<Reminder> {
        val now = System.currentTimeMillis()
        return ReminderStore.load(context).filter { it.triggerAt > now }.sortedBy { it.triggerAt }
    }

    private fun alarmIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", reminder.id)
            putExtra("text", reminder.text)
            putExtra("label", reminder.label)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + reminder.id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/** 到点：弹通知 + 语音播报 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text").orEmpty()
        val label = intent.getStringExtra("label").orEmpty()
        DebugLog.d("Reminder", "到点触发 #$id $text")

        ReminderNotifier.cancelCountdown(context, id)
        ReminderNotifier.showAlarm(context, text, label)

        // 提醒从列表里移除
        val rest = ReminderStore.load(context).filterNot { it.id == id }
        ReminderStore.save(context, rest)

        speak(context, if (text.isBlank()) "提醒时间到了" else "提醒你：$text")
    }

    private fun speak(context: Context, text: String) {
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            runCatching { tts?.shutdown() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                    })
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reminder")
                } else {
                    runCatching { tts?.shutdown() }
                }
            }
        } catch (e: Exception) {
            DebugLog.w("Reminder", "播报失败: ${e.message}")
        }
    }
}
