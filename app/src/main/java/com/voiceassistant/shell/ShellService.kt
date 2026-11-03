package com.voiceassistant.shell

import com.voiceassistant.log.DebugLog

/**
 * Shizuku UserService：运行在 Shizuku 创建的**独立进程**里，身份是 shell(2000)。
 *
 * 注意：这类服务**不能继承 Android 的 Service**。
 * 按 Shizuku 官方要求，类必须直接继承 AIDL 生成的 `IShellService.Stub`，
 * 由 Shizuku 反射实例化；也不需要（不能）在 AndroidManifest 里注册。
 *
 * 里面跑的代码等于 `adb shell`，可以 uiautomator dump / input tap / input keyevent。
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String = run(command)

    private fun run(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Throwable) {
            DebugLog.w("ShellService", "命令执行失败: ${e.message}")
            "ERR: ${e.message}"
        }
    }
}
