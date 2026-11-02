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
 * 按真机反馈改的三处：
 * 1. 微信停在上次的**聊天界面**时没有搜索入口 → 先 BACK 退回主列表再搜（最多 3 次）；
 * 2. 搜到多个相似联系人 → **语音问"第几个"**，不盲点第一个；
 * 3. 进聊天前二次核对，输入完成后再次确认才发送。
 */
class WechatExecutor(private val context: Context) {

    private companion object {
        const val TAG = "Wechat"
        const val WECHAT_PKG = "com.tencent.mm"
        const val STEP_TIMEOUT = 8_000L
        const val POLL_INTERVAL = 250L
        const val MAX_BACK = 3
    }

    suspend fun send(
        action: VaAction,
        confirm: suspend (String) -> Boolean,
        choose: suspend (List<String>) -> Int?
    ): ActionResult = withContext(Dispatchers.Main) {
        val contact = action.contact.trim()
        val message = action.message.trim()

        if (contact.isEmpty()) return@withContext fail(action, "没有听到联系人", "contact 为空")
        if (message.isEmpty()) return@withContext fail(action, "没有听到要发送的内容", "message 为空")
        if (!A11yBridge.isReady()) {
            return@withContext fail(action, "需要先开启无障碍服务才能发微信", "无障碍未连接")
        }

        if (!launchWechat()) return@withContext fail(action, "没有安装微信", "未找到 $WECHAT_PKG")
        waitForPackage(WECHAT_PKG)

        // 1. 找搜索入口：停在聊天界面时先返回（最多退 3 次）
        var searchEntry: AccessibilityNodeInfo? = null
        for (attempt in 0..MAX_BACK) {
            searchEntry = awaitNode("微信搜索入口", if (attempt == 0) 3000L else 2000L) {
                A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "搜索"))
                    ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "搜索"))
            }
            if (searchEntry != null) {
                if (attempt > 0) DebugLog.d(TAG, "返回 $attempt 次后找到搜索入口")
                break
            }
            DebugLog.d(TAG, "第 ${attempt + 1} 次没找到搜索入口，按返回")
            A11yBridge.back()
            delay(900)
        }
        if (searchEntry == null) {
            return@withContext fail(action, "找不到微信搜索入口", "连续返回 $MAX_BACK 次仍未出现搜索")
        }
        A11yBridge.click(searchEntry)

        // 2. 输入联系人：所有可编辑节点挨个试，谁能写进去就用谁
        val searchInput = typeIntoAnyEditable(contact, exclude = null)
            ?: return@withContext fail(action, "搜索框写不进文字（SET_TEXT/粘贴/菜单粘贴都失败）", "无可用可编辑节点")
        delay(1500)

        // 3. 收集候选（排除搜索框自己）
        val candidates = awaitNodes("联系人结果", 3000L) { findResults(contact, searchInput) }
        if (candidates.isEmpty()) {
            A11yBridge.back()
            return@withContext fail(action, "未找到联系人「$contact」，消息未发送", "搜索结果为空（已排除搜索框）")
        }

        // 4. 多个候选 → 语音问第几个
        val pickedIndex: Int
        if (candidates.size == 1) {
            pickedIndex = 0
        } else {
            val names = candidates.map { it.first }
            val idx = choose(names)
            if (idx == null || idx !in candidates.indices) {
                A11yBridge.back()
                return@withContext fail(
                    action,
                    "找到 ${candidates.size} 个相近联系人，没有选择，已取消",
                    "候选取消: $names"
                )
            }
            pickedIndex = idx
        }
        val matchedName = candidates[pickedIndex].first
        val resultNode = candidates[pickedIndex].second

        if (!confirm("搜索到「$matchedName」，是要发给TA吗？说确认或取消。")) {
            A11yBridge.back()
            return@withContext ActionResult(action, false, "已取消，没有进入聊天", "结果核对后用户取消")
        }

        if (!A11yBridge.click(resultNode)) {
            return@withContext fail(action, "点不开联系人「$matchedName」", "点击失败")
        }

        // 5. 输入消息：同样挨个试可编辑节点（排除搜索框）
        val chatInput = typeIntoAnyEditable(message, exclude = searchInput)
            ?: return@withContext fail(
                action,
                "没进到和「$matchedName」的聊天界面，或消息写不进去，未发送",
                "点击后没有可写入的聊天输入框"
            )
        delay(800)

        // 只做记录，不再因为读不到内容就中止（微信节点文本经常滞后）
        val typed = chatInput.text?.toString().orEmpty()
        DebugLog.d(TAG, "输入框回读内容=${if (typed.isBlank()) "(空/读不到)" else typed}")

        // 6. 输入完成后才确认发送
        if (!confirm("给${matchedName}发「$message」，确认发送吗？")) {
            return@withContext ActionResult(action, false, "已取消发送", "用户在输入完成后取消")
        }

        val sendButton = awaitNode("发送按钮", 4000L) {
            A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "发送"))
        } ?: return@withContext fail(action, "找不到发送按钮，消息未发送", "发送按钮缺失")

        if (!A11yBridge.click(sendButton)) {
            return@withContext fail(action, "点击发送失败", "click 返回 false")
        }

        delay(600)
        DebugLog.d(TAG, "消息已发送给 $matchedName")
        ActionResult(action, true, "消息已发送给$matchedName", "contact=$matchedName")
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

    private suspend fun awaitNode(
        label: String,
        timeoutMs: Long,
        finder: () -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val node = withTimeoutOrNull(timeoutMs) {
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

    private suspend fun awaitNodes(
        label: String,
        timeoutMs: Long,
        finder: () -> List<Pair<String, AccessibilityNodeInfo>>
    ): List<Pair<String, AccessibilityNodeInfo>> {
        val list = withTimeoutOrNull(timeoutMs) {
            var found: List<Pair<String, AccessibilityNodeInfo>> = emptyList()
            while (found.isEmpty()) {
                found = finder()
                if (found.isEmpty()) delay(POLL_INTERVAL)
            }
            found
        } ?: emptyList()
        if (list.isEmpty()) DebugLog.w(TAG, "等待「$label」超时")
        return list
    }

    /**
     * 把所有可编辑节点挨个试写入，谁成功就用谁。
     * 微信的分析：搜索框和聊天输入框都拒绝 SET_TEXT，逐个试 + 三种写入方式
     * （SET_TEXT / ACTION_PASTE / 长按菜单粘贴）能覆盖更多机型。
     */
    private suspend fun typeIntoAnyEditable(
        text: String,
        exclude: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? = withTimeoutOrNull(12_000) {
        var result: AccessibilityNodeInfo? = null
        while (result == null) {
            val nodes = A11yBridge.allEditables(exclude)
            // 已经有这段文字就直接用，绝不重复写
            result = nodes.firstOrNull { A11yBridge.nodeHasText(it, text) }
            if (result == null) {
                for (node in nodes) {
                    if (A11yBridge.setText(node, text)) {
                        result = node
                        break
                    }
                }
            }
            if (result == null) delay(POLL_INTERVAL)
        }
        result
    }

    private fun findEditable(exclude: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val root = A11yBridge.service()?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            val excluded = exclude != null && node == exclude
            if (!excluded && node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 联系人候选：文字完全相等优先，其次包含；排除搜索框和可编辑节点 */
    private fun findResults(
        contact: String,
        exclude: AccessibilityNodeInfo?
    ): List<Pair<String, AccessibilityNodeInfo>> {
        val root = A11yBridge.service()?.rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        val exact = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        val loose = ArrayList<Pair<String, AccessibilityNodeInfo>>()

        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            val excluded = exclude != null && node == exclude
            if (!excluded && !node.isEditable && node.isVisibleToUser) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val label = text.ifBlank { desc }
                if (label.isNotEmpty()) {
                    val target = clickableTarget(node)
                    when {
                        text == contact || desc == contact -> exact.add(label to target)
                        text.contains(contact) || desc.contains(contact) -> loose.add(label to target)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val pool = if (exact.isNotEmpty()) exact else loose
        return pool.distinctBy { label -> label.first }
            .distinctBy { pair -> System.identityHashCode(pair.second) }
            .take(5)
    }

    /** 找到可点的祖先（点文字节点本身常常没反应） */
    private fun clickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (node.isClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isClickable) return parent
            parent = parent.parent
            depth++
        }
        return node
    }
}
