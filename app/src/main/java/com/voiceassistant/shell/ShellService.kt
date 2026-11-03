package com.voiceassistant.shell

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 由 Shizuku 以 shell 权限启动的服务。
 * 它执行的命令等同于 `adb shell`，可以用来 uiautomator dump / input tap / input keyevent 等。
 */
class ShellService : Service() {

    private val binder = object : IShellService.Stub() {
        override fun exec(command: String): String = run(command)
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
