package com.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.voiceassistant.R
import com.voiceassistant.ui.MainActivity

/**
 * 常驻通知：监听状态 + 停止/暂停/打开应用。
 * 前台服务必须挂一个持续通知，这也是用户感知「在录音」的唯一可靠信号。
 */
object NotificationHelper {

    const val CHANNEL_ID = "va_listening"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_listening),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_listening_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, keyword: String, paused: Boolean): Notification {
        ensureChannel(context)

        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, WakeService::class.java).setAction(WakeService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = PendingIntent.getService(
            context, 2,
            Intent(context, WakeService::class.java)
                .setAction(if (paused) WakeService.ACTION_RESUME else WakeService.ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (paused) {
            context.getString(R.string.notification_title_paused)
        } else {
            context.getString(R.string.notification_title_listening)
        }
        val text = if (paused) {
            "点「继续」恢复监听"
        } else {
            "唤醒词：$keyword"
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(if (paused) R.string.notification_action_resume else R.string.notification_action_pause),
                toggleIntent
            )
            .addAction(0, context.getString(R.string.notification_action_stop), stopIntent)
            .addAction(0, context.getString(R.string.notification_action_open), openApp)
            .build()
    }
}
