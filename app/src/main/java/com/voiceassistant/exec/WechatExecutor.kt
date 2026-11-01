package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceassistant.log.DebugLog
import com.voiceassistant.nlu.VaAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 微信发送执行器（无障碍）。
 *
 * 踩过的坑（日志里"进不去聊天界面"就是它）：
 * 搜索框本身是个可编辑节点，输入完联系人后，**搜索框里就有这串字**，
 * 如果按文本找"结果"，会把搜索框自己点一遍，然后一直停在搜索结果页。
 *
 * 所以这里的做法是：
 * 1. 记住搜索框节点，后面所有"找结果""找输入框"都把它排除掉；
 * 2. 点结果后必须等到一个**不同于搜索框**的可编辑节点，才算进了聊天界面；
 * 3. 每一步都有超时和明确的失败原因，找不到就停，绝不盲点。
 */
class WechatExecutor(private val context: Context) {

    private companion object {
        const val TAG = "Wechat"
        const val WECHAT_PKG = "com.tencent.mm"
        const val STEP_TIMEOUT = 8_000L
        const val POLL_INTERVAL = 250L
    }

    suspend fun send(action: VaAction, confirm: suspend (String) -> Boolean): ActionResult =
        withContext(Dispatchers.Main) {
        val contact = action.contact.trim()
        val message = action.message.trim()

        if (contact.isEmpty()) return@withContext fail(action, "没有听到联系人", "contact 为空")
        if (message.isEmpty()) return@withContext fail(action, "没有听到要发送的内容", "message 为空")
        if (!A11yBridge.isReady()) {
            return@withContext fail(action, "需要先开启无障碍服务才能发微信", "无障碍未连接")
        }

        // 1. 打开微信
        if (!launchWechat()) return@withContext fail(action, "没有安装微信", "未找到 $WECHAT_PKG")
        waitForPackage(WECHAT_PKG)

        // 2. 点搜索
        val searchEntry = awaitNode("微信搜索入口") {
            A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "搜索"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "搜索"))
        }
        if (searchEntry != null) A11yBridge.click(searchEntry)

        // 3. 找到搜索框并记住它（后面要排除）
        val searchInput = awaitNode("搜索输入框") { findEditable(exclude = null) }
            ?: return@withContext fail(action, "微信界面变化了，找不到搜索框", "editable 节点缺失")

        if (!A11yBridge.setText(searchInput, contact)) {
            return@withContext fail(action, "输入联系人失败", "ACTION_SET_TEXT 失败")
        }
        delay(1500)

        // 4. 点搜索结果：文本匹配，但**不能是搜索框本身**
        val resultNode = awaitNode("联系人结果") { findResult(contact, exclude = searchInput) }
        if (resultNode == null) {
            A11yBridge.back()
            return@withContext fail(action, "未找到联系人「$contact」，消息未发送", "搜索结果为空（已排除搜索框）")
        }

        // 进聊天之前先核对结果，避免发错人
        val matchedName = resultNode.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: contact
        if (!confirm("搜索到「$matchedName」，是要发给TA吗？说确认或取消。")) {
            A11yBridge.back()
            return@withContext ActionResult(action, false, "已取消，没有进入聊天", "结果核对后用户取消")
        }

        if (!A11yBridge.click(resultNode)) {
            return@withContext fail(action, "点不开联系人「$contact」", "点击失败")
        }

        // 5. 等一个「不是搜索框」的输入框 = 真正进了聊天界面
        val chatInput = awaitNode("聊天输入框") { findEditable(exclude = searchInput) }
        if (chatInput == null) {
            return@withContext fail(
                action,
                "没进到和「$contact」的聊天界面，消息未发送",
                "点击后仍未出现聊天输入框"
            )
        }

        if (!A11yBridge.setText(chatInput, message)) {
            return@withContext fail(action, "输入消息失败", "ACTION_SET_TEXT 失败")
        }
        delay(800)

        // 先确认消息真的落到了输入框里（不然回车会发出去个空消息）
        val typed = chatInput.text?.toString().orEmpty()
        if (typed.isBlank()) {
            return@withContext fail(action, "消息没输进去，已中止发送", "输入框内容为空")
        }

        // 输入完成之后才做语音确认——顺序反过来更容易确认"发的就是这个"
        if (!confirm("给${matchedName}发「$message」，确认发送吗？")) {
            return@withContext ActionResult(action, false, "已取消发送", "用户在输入完成后取消")
        }

        // 发送：有 Shizuku 就模拟回车（最贴近真人操作），否则点「发送」按钮
        if (ShellExecutor.granted() && ShellExecutor.keyEvent(66)) {
            delay(600)
            DebugLog.d(TAG, "已用回车发送给 $contact")
            return@withContext ActionResult(action, true, "消息已发送给$contact", "enter via shizuku, contact=$contact")
        }

        val sendButton = awaitNode("发送按钮") {
            A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "发送"))
        } ?: return@withContext fail(action, "找不到发送按钮，消息未发送", "发送按钮缺失")

        if (!A11yBridge.click(sendButton)) {
            return@withContext fail(action, "点击发送失败", "click 返回 false")
        }

        delay(600)
        DebugLog.d(TAG, "消息已发送给 $contact")
        ActionResult(action, true, "消息已发送给$contact", "contact=$contact")
    }

    private fun fail(action: VaAction, speech: String, detail: String): ActionResult {
        DebugLog.w(TAG, "失败: $speech ($detail)")
        return ActionResult(action, false, speech, detail)
    }

    private fun launchWechat(): Boolean = try {
        val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PKG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        DebugLog.e(TAG, "启动微信失败", e)
        false
    }

    private suspend fun waitForPackage(pkg: String) {
        withTimeoutOrNull(STEP_TIMEOUT) {
            while (A11yBridge.currentWindowPackage() != pkg) delay(POLL_INTERVAL)
        }
    }

    /** 轮询等待节点出现 */
    private suspend fun awaitNode(
        label: String,
        finder: () -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val node = withTimeoutOrNull(STEP_TIMEOUT) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) {
                found = finder()
                if (found == null) delay(POLL_INTERVAL)
            }
            found
        }
        if (node == null) DebugLog.w(TAG, "等待「$label」超时")
        return node
    }

    /** 可编辑节点；[exclude] 用于排除搜索框 */
    private fun findEditable(exclude: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val root = A11yBridge.service()?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            val isExcluded = exclude != null && node == exclude
            if (!isExcluded && node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * 联系人搜索结果：文本等于/包含联系人，排除搜索框，优先可点或父节点可点。
     */
    private fun findResult(contact: String, exclude: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val root = A11yBridge.service()?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var fallback: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            val excluded = exclude != null && node == exclude
            if (!excluded && !node.isEditable) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val matched = text == contact || desc == contact ||
                    text.contains(contact) || desc.contains(contact)
                if (matched && node.isVisibleToUser) {
                    if (node.isClickable) return node
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 4) {
                        if (parent.isClickable) return parent
                        parent = parent.parent
                        depth++
                    }
                    if (fallback == null) fallback = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallback
    }
}
