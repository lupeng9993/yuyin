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
 * 现实约束：微信没有对外接口，只能靠无障碍遍历界面节点，微信一改版就可能失效。
 * 因此这里全程按「步骤 + 超时 + 明确失败原因」实现，任何一步找不到就停下并回报，
 * 绝不盲点，避免误发消息。
 */
class WechatExecutor(private val context: Context) {

    private companion object {
        const val TAG = "Wechat"
        const val WECHAT_PKG = "com.tencent.mm"
        const val STEP_TIMEOUT = 8_000L
        const val POLL_INTERVAL = 250L
    }

    suspend fun send(action: VaAction): ActionResult = withContext(Dispatchers.Main) {
        val contact = action.contact.trim()
        val message = action.message.trim()

        if (contact.isEmpty()) return@withContext fail(action, "没有听到联系人", "contact 为空")
        if (message.isEmpty()) return@withContext fail(action, "没有听到要发送的内容", "message 为空")

        if (!A11yBridge.isReady()) {
            return@withContext fail(action, "需要先开启无障碍服务才能发微信", "无障碍未连接")
        }

        // 1. 启动微信
        if (!launchWechat()) {
            return@withContext fail(action, "没有安装微信", "未找到 $WECHAT_PKG")
        }
        waitForPackage(WECHAT_PKG)

        // 2. 点搜索
        val searchEntry = awaitNode("微信搜索入口") {
            A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "搜索"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "搜索"))
        }
        if (searchEntry != null && !A11yBridge.click(searchEntry)) {
            DebugLog.w(TAG, "点击搜索入口失败，继续尝试直接找输入框")
        }

        // 3. 输入联系人
        val input = awaitNode("搜索输入框") { findEditable() }
            ?: return@withContext fail(action, "微信界面变化了，找不到搜索框", "editable 节点缺失")

        if (!A11yBridge.setText(input, contact)) {
            return@withContext fail(action, "输入联系人失败", "ACTION_SET_TEXT 失败")
        }
        delay(1200)

        // 4. 点搜索结果
        val resultNode = awaitNode("联系人结果") {
            A11yBridge.findNode(A11yBridge.TextFilter(exact = contact))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = contact))
        }
        if (resultNode == null) {
            A11yBridge.back()
            return@withContext fail(action, "未找到联系人「$contact」，消息未发送", "搜索结果为空")
        }
        if (!A11yBridge.click(resultNode)) {
            return@withContext fail(action, "点不开联系人「$contact」", "点击失败")
        }
        delay(1200)

        // 5. 聊天输入框
        val chatInput = awaitNode("聊天输入框") { findEditable() }
            ?: return@withContext fail(action, "进不到聊天界面", "聊天输入框缺失")

        if (!A11yBridge.setText(chatInput, message)) {
            return@withContext fail(action, "输入消息失败", "ACTION_SET_TEXT 失败")
        }
        delay(600)

        // 6. 发送
        val sendButton = awaitNode("发送按钮") {
            A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "发送"))
                ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "发送"))
        } ?: return@withContext fail(action, "找不到发送按钮，消息未发送", "发送按钮缺失")

        if (!A11yBridge.click(sendButton)) {
            return@withContext fail(action, "点击发送失败", "click 返回 false")
        }

        delay(500)
        DebugLog.d(TAG, "消息已发送给 $contact")
        ActionResult(action, true, "消息已发送给$contact", "contact=$contact")
    }

    private fun fail(action: VaAction, speech: String, detail: String): ActionResult {
        DebugLog.w(TAG, "失败: $speech ($detail)")
        return ActionResult(action, false, speech, detail)
    }

    private fun launchWechat(): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PKG)
                ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            DebugLog.e(TAG, "启动微信失败", e)
            false
        }
    }

    private suspend fun waitForPackage(pkg: String) {
        withTimeoutOrNull(STEP_TIMEOUT) {
            while (A11yBridge.currentWindowPackage() != pkg) {
                delay(POLL_INTERVAL)
            }
        }
    }

    /** 轮询等待节点出现，超时返回 null */
    private suspend fun awaitNode(label: String, finder: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo? {
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

    private fun findEditable(): AccessibilityNodeInfo? {
        val root = A11yBridge.service()?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }
}
