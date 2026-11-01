package com.voiceassistant.exec

/**
 * Shell 能力预留位。
 *
 * 说明：想通过 Shizuku 执行 `am force-stop` / `input keyevent` 需要绑定 UserService
 * （`Shizuku.bindUserService` + 自己实现 IUserService.aidl），不是一行 `newProcess` 能解决的；
 * 当前 Shizuku 13.x 里 `Shizuku.newProcess` 还是 private，所以这条路先不启用。
 *
 * 现阶段「关闭应用」走的是：
 *   1. 无障碍回桌面 → killBackgroundProcesses → 验证
 *   2. 仍未关闭则用无障碍在「应用详情」页点「强行停止」
 *   3. 都失败就如实播报，不假报成功
 *
 * 以后接 Shizuku 时只要把这里三个方法实现掉，调用方（SystemExecutor / WechatExecutor）不用改。
 */
object ShellExecutor {

    /** 是否具备 shell 级能力 */
    fun granted(): Boolean = false

    /** 强制停止某个包 */
    fun forceStop(pkg: String): Boolean = false

    /** 模拟按键（66 = 回车） */
    fun keyEvent(keyCode: Int): Boolean = false

    fun requestPermission() = Unit

    fun stateText(): String = "未启用（当前用无障碍方案，可正常关闭前台应用）"
}
