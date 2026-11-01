package com.voiceassistant.exec

import android.content.pm.PackageManager
import com.voiceassistant.log.DebugLog
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku 执行 shell 命令（需要用户安装 Shizuku 并授权）。
 *
 * 用途：真正停掉别的应用（`am force-stop`）、以及模拟回车键发送消息。
 * 没有 Shizuku 或未授权时所有方法都会安全地返回 false，调用方走原有兜底逻辑。
 */
object ShellExecutor {

    private const val TAG = "Shizuku"

    const val PERMISSION_REQUEST_CODE = 4213

    /** Shizuku 服务是否在运行 */
    fun installed(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 是否已获得授权 */
    fun granted(): Boolean = try {
        installed() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** 弹授权请求（需要在前台调用，结果由 Shizuku App 自己弹窗） */
    fun requestPermission() {
        try {
            if (!installed()) {
                DebugLog.w(TAG, "Shizuku 没有运行")
                return
            }
            if (granted()) return
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            DebugLog.w(TAG, "请求授权失败: ${e.message}")
        }
    }

    /** 执行一条命令，返回是否 exit=0 */
    fun run(vararg command: String): Boolean {
        if (!granted()) return false
        return try {
            val process = Shizuku.newProcess(command, null, null)
            val exit = process.waitFor()
            DebugLog.d(TAG, "${command.joinToString(" ")} → exit=$exit")
            exit == 0
        } catch (e: Throwable) {
            DebugLog.e(TAG, "执行失败 ${command.joinToString(" ")}", e)
            false
        }
    }

    fun forceStop(pkg: String): Boolean = run("am", "force-stop", pkg)

    /** 模拟按键；66 = 回车 */
    fun keyEvent(keyCode: Int): Boolean = run("input", "keyevent", keyCode.toString())

    fun stateText(): String = when {
        !installed() -> "未检测到 Shizuku（需先安装并启动）"
        !granted() -> "已检测到 Shizuku，但未授权"
        else -> "Shizuku 已授权"
    }
}
