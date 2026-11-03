package com.voiceassistant.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.voiceassistant.log.DebugLog
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道：拿到 shell 权限后执行命令。
 *
 * 用法：
 *  1. ShizukuShell.isAvailable()  → Shizuku 服务在跑吗
 *  2. ShizukuShell.hasPermission() → 授权了吗（没有就 requestPermission）
 *  3. ShizukuShell.exec("uiautomator dump ...") → 执行
 */
object ShizukuShell {

    private const val TAG = "Shizuku"
    const val REQUEST_CODE = 4213

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var binding = false

    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isAvailable() && Shizuku.checkSelfPermission() == 0
    } catch (e: Throwable) {
        false
    }

    /** 必须从 Activity 调用；结果通过 [Shizuku.addRequestPermissionResultListener] 回调 */
    fun requestPermission(): Boolean = try {
        if (!isAvailable()) {
            DebugLog.w(TAG, "Shizuku 服务没在运行")
            false
        } else if (hasPermission()) {
            true
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            false
        }
    } catch (e: Throwable) {
        DebugLog.e(TAG, "请求 Shizuku 权限失败", e as? Exception)
        false
    }

    /** 绑定 UserService（幂等） */
    fun ensureService(context: Context) {
        if (service != null || binding) return
        if (!hasPermission()) {
            DebugLog.w(TAG, "还没拿到 Shizuku 权限")
            return
        }
        try {
            binding = true
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, ShellService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IShellService.Stub.asInterface(binder)
                    binding = false
                    DebugLog.d(TAG, "ShellService 已连接")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    binding = false
                    DebugLog.w(TAG, "ShellService 已断开")
                }
            }
            boundArgs = args
            connection = conn
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            binding = false
            DebugLog.e(TAG, "绑定 ShellService 失败", e as? Exception)
        }
    }

    fun unbind() {
        val args = boundArgs ?: return
        val conn = connection ?: return
        try {
            Shizuku.unbindUserService(args, conn, true)
        } catch (e: Throwable) {
            // 忽略
        }
        service = null
        binding = false
    }

    /** 同步执行；服务没准备好会等最多 5 秒 */
    fun exec(command: String, context: Context): String {
        ensureService(context)
        var waited = 0
        while (service == null && waited < 5000) {
            Thread.sleep(100)
            waited += 100
            if (waited % 1000 == 0) ensureService(context)
        }
        val svc = service ?: return "ERR: Shizuku 服务未就绪（先授权并确认 Shizuku 在运行）"
        return try {
            svc.exec(command)
        } catch (e: Exception) {
            DebugLog.e(TAG, "执行命令失败: $command", e)
            "ERR: ${e.message}"
        }
    }
}
