package com.voiceassistant.shell

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import com.voiceassistant.log.DebugLog

/**
 * 由 Shizuku 以 shell 权限启动的服务。
 * 它执行的命令等同于 `adb shell`，可以用来 uiautomator dump / input tap / input keyevent 等。
 *
 * 安全：exported=true 才能被 shell 身份的 Shizuku 绑定，
 * 所以这里只接受「shell(2000) / root(0) / 本应用」三种调用者。
 */
class ShellService : Service() {

    private val binder = object : IShellService.Stub() {
        override fun exec(command: String): String {
            val uid = Binder.getCallingUid()
            val myUid = Process.myUid()
            val allowed = uid == myUid || uid == 2000 || uid == 0
            if (!allowed) {
                DebugLog.w("ShellService", "拒绝来自 uid=$uid 的命令")
                return "ERR: permission denied (uid=$uid)"
            }
            return run(command)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun run(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERR: ${e.message}"
        }
    }
}
