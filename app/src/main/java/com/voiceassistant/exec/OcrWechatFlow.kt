package com.voiceassistant.exec

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import com.voiceassistant.log.DebugLog
import com.voiceassistant.shell.OcrHelper
import com.voiceassistant.shell.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 基于「截图 + OCR + shell 点击」的微信发送流程。
 *
 * 为什么需要它：车机版微信是自绘界面，无障碍和 uiautomator 都读不到节点
 * （dump 只返回一个 bounds=[0,0][0,0] 的空节点），只能靠 OCR 认字、按坐标点。
 *
 * 每一步都做 OCR 验证，不盲点：
 *   找到「微信(N)」标题 → 在标题右侧几个候选点试点放大镜 → 出现「搜索」即成功
 *   → 点搜索框、粘贴联系人 → OCR 找到联系人行并点击
 *   → 粘贴消息 → 多轮追加 → OCR 找到「发送」并点击 → OCR 复核气泡
 */
class OcrWechatFlow(private val context: Context) {

    private companion object {
        const val TAG = "OcrWechat"
        const val WECHAT_PKG = "com.tencent.mm"
        const val SCREEN_W = 1920
        const val SCREEN_H = 1200
    }

    /** 一次结果：非 null 表示这条路径已处理（成功或明确失败），null 表示不适用、交给旧路径 */
    data class Result(val ok: Boolean, val speech: String, val detail: String)

    suspend fun send(
        contact: String,
        message: String,
        choose: suspend (List<String>) -> Int?,
        listen: suspend (String, Long) -> String?
    ): Result? {
        if (!ShizukuShell.isAvailable()) {
            DebugLog.d(TAG, "Shizuku 不可用，走旧路径")
            return null
        }
        if (!ShizukuShell.hasPermission()) {
            DebugLog.w(TAG, "没有 Shizuku 权限，尝试发起授权")
            ShizukuShell.requestPermission()
            return Result(false, "需要授权 Shizuku，请在弹出的窗口点允许，然后再说一次", "no permission")
        }
        ShizukuShell.ensureService(context)

        launchWechat()
        delay(2000)

        // 1. 当前必须在微信（OCR 能看到微信的特征文字）
        var screen = ocr()
        if (screen.isEmpty()) {
            return Result(false, "截图或识别失败，没法操作微信", "ocr empty")
        }
        if (!inWechat(screen)) {
            DebugLog.w(TAG, "当前不在微信，重新拉起")
            launchWechat()
            delay(2500)
            screen = ocr()
            if (!inWechat(screen)) {
                return Result(false, "没能打开微信", "not wechat after relaunch")
            }
        }

        // 2. 进入搜索：点标题右侧的放大镜，点完用 OCR 验证是否出现「搜索」
        if (!screen.any { it.text.contains("搜索") }) {
            val opened = enterSearch()
            if (!opened) {
                return Result(false, "没能点开微信搜索", "magnifier candidates all failed")
            }
            delay(1200)
            screen = ocr()
        }

        // 3. 点搜索框（找「搜索」提示文字）并粘贴联系人
        val searchHint = screen.firstOrNull { it.text.contains("搜索") && it.cy < SCREEN_H / 3 }
            ?: screen.firstOrNull { it.text.contains("搜索") }
        if (searchHint != null) {
            tap(searchHint.cx, searchHint.cy)
            delay(700)
        }
        pasteText(contact)
        delay(1500)
        screen = ocr()

        // 4. 结果里找联系人：**只认右栏**（搜索框与结果都在右栏，左栏是聊天列表）
        //    并排除消息摘要行（带冒号或方括号的那种）
        val rightPane = SCREEN_W / 2
        val rows = screen.filter {
            it.text.contains(contact) &&
                it.cx > rightPane &&
                it.cy > SCREEN_H / 8 &&
                it.cy < SCREEN_H * 0.92 &&
                !it.text.contains(":") && !it.text.contains("：") && !it.text.contains("[")
        }
        if (rows.isEmpty()) {
            back()
            return Result(false, "没找到联系人「$contact」", "no right-pane row matched")
        }

        val exact = rows.filter { it.text.trim() == contact }
        val target: OcrHelper.Item = when {
            exact.size == 1 -> exact.first()
            rows.size == 1 -> rows.first()
            else -> {
                val names = rows.map { it.text }
                val idx = choose(names)
                if (idx == null || idx !in rows.indices) {
                    back()
                    return Result(false, "有 ${rows.size} 个匹配，没有选择，已取消", "candidate cancelled")
                }
                rows[idx]
            }
        }
        DebugLog.d(TAG, "点击联系人行：${target.label}")
        tap(target.cx, target.cy)
        delay(2000)

        // 5. 聊天界面：点底部输入区再粘贴
        tapInputArea()
        delay(600)
        pasteText(message)
        delay(1200)

        // 6. 多轮追加
        var body = message
        var rounds = 0
        var sendNow = false
        while (rounds < 3 && !sendNow) {
            rounds++
            val answer = listen("还有要添加的吗？没有就说发送", 6_000)
            if (answer == null) {
                val again = listen("要发送吗", 6_000)
                if (again == null || !wantsSend(again)) {
                    back()
                    return Result(false, "没有发送，已取消", "timeout cancel")
                }
                sendNow = true
                break
            }
            val said = answer.trim()
            if (wantsSend(said)) {
                sendNow = true
                break
            }
            if (said.isNotEmpty()) {
                body = if (body.isBlank()) said else "$body $said"
                tapInputArea()
                delay(400)
                pasteText(body)
                delay(900)
            }
        }

        // 7. 点「发送」（OCR 定位文字）
        val sendBtn = ocr().firstOrNull { it.text.trim() == "发送" || it.text.trim() == "发送!" }
            ?: ocr().firstOrNull { it.text.contains("发送") && it.cy > SCREEN_H * 0.6 }
        if (sendBtn == null) {
            return Result(false, "找不到发送按钮，消息留在输入框里了", "send button not found by OCR")
        }
        DebugLog.d(TAG, "点击发送：${sendBtn.label}")
        tap(sendBtn.cx, sendBtn.cy)
        delay(1500)

        // 8. 复核：聊天区里出现这条消息
        val after = ocr()
        val key = body.take(10)
        val appeared = after.any { it.text.replace(" ", "").contains(key.replace(" ", "")) }
        return Result(
            appeared,
            if (appeared) "已发送" else "点了发送，但没看到消息，请确认一下",
            "appeared=$appeared rounds=$rounds"
        )
    }

    // ---------------- 基础操作 ----------------

    private suspend fun ocr(): List<OcrHelper.Item> = withContext(Dispatchers.IO) {
        val file = OcrHelper.capture(context) ?: return@withContext emptyList()
        val items = OcrHelper.recognize(file)
        file.delete()
        items
    }

    private fun inWechat(screen: List<OcrHelper.Item>): Boolean =
        screen.any { it.text.contains("微信") } ||
            screen.any { it.text.contains("通讯录") || it.text.contains("通訊录") } ||
            screen.any { it.text.contains("搜索") }

    private fun launchWechat() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PKG) ?: return
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLog.w(TAG, "拉起微信失败: ${e.message}")
        }
    }

    /** 依次试标题右侧的候选点，直到 OCR 里出现「搜索」 */
    private suspend fun enterSearch(): Boolean {
        // 优先用「校准」时你亲手点的位置
        val saved = com.voiceassistant.ui.TapCaptureActivity.load(context)
        if (saved != null) {
            DebugLog.d(TAG, "使用校准坐标 (${saved.first},${saved.second}) 点搜索")
            tap(saved.first, saved.second)
            delay(1200)
            if (ocr().any { it.text.contains("搜索") }) {
                DebugLog.d(TAG, "校准坐标有效，已进入搜索")
                return true
            }
            DebugLog.w(TAG, "校准坐标没进搜索，继续用候选点试")
        }

        val screen = ocr()
        val title = screen.firstOrNull { it.text.contains("微信") && it.cy < SCREEN_H / 4 }
        val baseY = title?.cy ?: 160
        // 放大镜在左栏标题右侧；测得的截图里约 x=1030，附近的几个候选都试一遍
        val candidates = listOf(
            1030 to baseY,
            1000 to baseY,
            1060 to baseY,
            900 to baseY,
            860 to baseY,
            1030 to (baseY + 20)
        )
        for ((x, y) in candidates) {
            DebugLog.d(TAG, "试点放大镜 ($x,$y)")
            tap(x, y)
            delay(900)
            val now = ocr()
            if (now.any { it.text.contains("搜索") }) {
                DebugLog.d(TAG, "进入搜索成功，位置 ($x,$y)")
                return true
            }
            if (!inWechat(now)) {
                DebugLog.w(TAG, "点完跑出微信了，重新拉起")
                launchWechat()
                delay(2000)
            }
        }
        return false
    }

    private suspend fun tap(x: Int, y: Int) {
        withContext(Dispatchers.IO) { ShizukuShell.exec("input tap $x $y", context) }
    }

    private suspend fun back() {
        withContext(Dispatchers.IO) { ShizukuShell.exec("input keyevent 4", context) }
    }

    /** 点聊天底部输入区（右栏底部靠左中位置） */
    private suspend fun tapInputArea() {
        tap(SCREEN_W * 3 / 4, (SCREEN_H * 0.88f).toInt())
    }

    /** 剪贴板写入后按 KEYCODE_PASTE(279) */
    private suspend fun pasteText(text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("va", text))
        } catch (e: Exception) {
            DebugLog.w(TAG, "写剪贴板失败: ${e.message}")
        }
        withContext(Dispatchers.IO) { ShizukuShell.exec("input keyevent 279", context) }
    }

    private fun wantsSend(text: String): Boolean {
        val t = text.trim().trimEnd('。', '.', '，', ',', '!', '！')
        if (t == "没有" || t == "不用了" || t == "没有了") return true
        return t.endsWith("发送") || t.endsWith("发送吧") || t == "发"
    }
}
