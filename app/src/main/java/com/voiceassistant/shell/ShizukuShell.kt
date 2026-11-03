package com.voiceassistant.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.voiceassistant.log.DebugLog
import rikka.shizuku.Shizuku

/**
 * Shizuku 通道：拿到 shell 权限后执行命令（等效 adb shell）。
 *
 * 关键点（踩过的坑）：
 * - `bindUserService` 必须在主线程调用，否则连接回调可能不触发
 * - Shizuku 拉起 UserService 需要时间，首次可能要几秒
 * - Shizuku 的 binder 还没就绪时直接 bind 会失败，所以先挂监听、就绪后再绑
 */
object ShizukuShell {

    private const val TAG = "Shizuku"
    const val REQUEST_CODE = 4213
    private const val WAIT_MS = 15_000L

    @Volatile
    private var shell: IShellService? = null

    @Volatile
    private var binding = false

    private val main = Handler(Looper.getMainLooper())

    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        DebugLog.d(TAG, "Shizuku binder 已就绪")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shell = null
        binding = false
        DebugLog.w(TAG, "Shizuku binder 已断开")
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grant ->
        DebugLog.d(TAG, "权限结果 code=$code grant=$grant")
    }

    /** 注册监听，App 启动时调用一次 */
    fun init(context: Context) {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
            DebugLog.d(TAG, "Shizuku 监听已注册，可用=${isAvailable()} 权限=${hasPermission()}")
        } catch (e: Throwable) {
            DebugLog.w(TAG, "注册 Shizuku 监听失败: ${e.message}")
        }
        // 已授权就提前绑好，省掉首次使用时的等待
        if (hasPermission()) ensureService(context)
    }

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

    /** 必须从 Activity 调用 */
    fun requestPermission(): Boolean = try {
        when {
            !isAvailable() -> {
                DebugLog.w(TAG, "Shizuku 服务没在运行")
                false
            }
            hasPermission() -> true
            else -> {
                Shizuku.requestPermission(REQUEST_CODE)
                false
            }
        }
    } catch (e: Throwable) {
        DebugLog.w(TAG, "请求权限失败: ${e.message}")
        false
    }

    /** 绑定 UserService（幂等，主线程执行） */
    fun ensureService(context: Context) {
        if (shell != null || binding) return
        if (!hasPermission()) {
            DebugLog.w(TAG, "还没拿到 Shizuku 权限，跳过绑定")
            return
        }
        if (!isAvailable()) {
            DebugLog.w(TAG, "Shizuku binder 未就绪，等它起来再绑")
            return
        }
        main.post {
            if (shell != null || binding) return@post
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
                        shell = IShellService.Stub.asInterface(binder)
                        binding = false
                        DebugLog.d(TAG, "ShellService 已连接")
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        shell = null
                        binding = false
                        DebugLog.w(TAG, "ShellService 已断开")
                    }
                }
                boundArgs = args
                connection = conn
                DebugLog.d(TAG, "bindUserService 已发起")
                Shizuku.bindUserService(args, conn)
            } catch (e: Throwable) {
                binding = false
                DebugLog.w(TAG, "绑定失败: ${e.message}")
            }
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
        shell = null
        binding = false
    }

    /** 同步执行；最多等 15 秒，期间每秒打一行进度 */
    fun exec(command: String, context: Context): String {
        if (shell == null) {
            ensureService(context)
            var waited = 0L
            while (shell == null && waited < WAIT_MS) {
                Thread.sleep(500)
                waited += 500
                if (waited % 3000L == 0L) {
                    DebugLog.d(TAG, "等待 ShellService…(${waited}ms) 绑定中=$binding 可用=${isAvailable()} 权限=${hasPermission()}")
                    if (!binding) ensureService(context)
                }
            }
        }
        val svc = shell ?: return "ERR: ShellService 未连接（授权=${hasPermission()} 可用=${isAvailable()} 绑定中=$binding）"
        return try {
            svc.exec(command)
        } catch (e: Exception) {
            DebugLog.e(TAG, "执行失败: $command", e)
            "ERR: ${e.message}"
        }
    }
}
