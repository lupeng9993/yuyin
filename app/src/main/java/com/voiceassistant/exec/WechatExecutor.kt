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
class WechatExecutor(
    private val context: Context,
    /** 发送键相对输入框右边缘的偏移（dp），车机/手机不一样，可在设置里调 */
    private val offsetDp: () -> Float = { 8f }
) {

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

        // 1. 找搜索入口。关键：**只在"不在列表页"时才按返回**
        //    之前无脑连按 4 次返回，把微信整个退到桌面，后面点什么都点不到。
        var searchEntry: AccessibilityNodeInfo? = null
        for (attempt in 0..MAX_BACK) {
            searchEntry = awaitNode("微信搜索入口", if (attempt == 0) 2500L else 1500L) { findSearchEntry() }
            if (searchEntry != null) {
                if (attempt > 0) DebugLog.d(TAG, "返回 $attempt 次后找到搜索入口")
                break
            }
            if (isOnMainList()) {
                DebugLog.d(TAG, "已在微信列表页，停止按返回，改用图标定位")
                break
            }
            DebugLog.d(TAG, "不在列表页，按返回（第 ${attempt + 1} 次）")
            A11yBridge.back()
            delay(800)
        }

        // 2. 点进去并**校验真的进了搜索**（有可编辑节点才算成功）
        var entered = false
        if (searchEntry != null) {
            A11yBridge.click(searchEntry)
            delay(900)
            entered = hasEditable()
            if (entered) DebugLog.d(TAG, "已进入微信搜索页")
        }

        if (!entered) {
            // 车机版：搜索是纯放大镜图标，按布局点；点完校验，没中换个偏移再来
            for (offset in listOf(0, -16, 16, -32)) {
                DebugLog.w(TAG, "按布局点放大镜（偏移 $offset dp）")
                if (clickSearchIconByLayout(offset)) {
                    delay(1300)
                    if (hasEditable()) {
                        entered = true
                        DebugLog.d(TAG, "放大镜点击成功（偏移 $offset dp）")
                        break
                    }
                }
            }
        }

        if (!entered) {
            A11yBridge.dumpTree("NodeDump")
            return@withContext fail(
                action,
                "没能进入微信搜索",
                "点了搜索图标但没出现输入框（已导出节点树）"
            )
        }

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

        val sendLevel = doSend(chatInput)
        if (sendLevel == null) {
            return@withContext fail(action, "找不到发送按钮，消息未发送", "四级发送全部失败")
        }
        delay(600)
        DebugLog.d(TAG, "消息已发送给 $matchedName（发送方式：$sendLevel）")
        ActionResult(action, true, "消息已发送给$matchedName", "contact=$matchedName send=$sendLevel")
    }

    /**
     * 四级发送，按可靠性排序。
     * 车机版微信是左右分栏、发送键没有文字；手机版发送键常在输入法里。
     * 所以不假设位置：先走系统输入法动作，再找文字，再按输入框右侧坐标点。
     */
    private suspend fun doSend(chatInput: AccessibilityNodeInfo): String? {
        // 0. 先点一下输入框，让键盘弹出、输入框获得焦点
        A11yBridge.click(chatInput)
        delay(500)

        // 1. 系统级「发送」动作（等价按键盘发送键）
        if (A11yBridge.imeEnter(chatInput)) {
            DebugLog.d(TAG, "发送级别 1：ACTION_IME_ENTER")
            return "IME_ENTER"
        }

        // 2. 文字/描述里带「发送」的节点
        val byText = A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "发送"))
            ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "发送"))
            ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "发送"))
        if (byText != null && A11yBridge.click(byText)) {
            DebugLog.d(TAG, "发送级别 2：文字匹配 ${byText.className}")
            return "文字匹配"
        }

        // 3. 按输入框右侧坐标点（车机版/手机版共用，偏移可在设置里调）
        val rect = android.graphics.Rect()
        chatInput.getBoundsInScreen(rect)
        val density = context.resources.displayMetrics.density
        val offsetPx = (offsetDp() * density).toInt().coerceAtLeast(8)
        val x = (rect.right - offsetPx).coerceIn(0, rect.right)
        val y = rect.centerY()
        if (A11yBridge.clickByGesture(x, y)) {
            DebugLog.d(TAG, "发送级别 3：坐标点击 ($x, $y) 输入框=$rect")
            return "坐标($x,$y)"
        }

        // 4. 兜底：右下角区域里最小的可点节点
        val fallback = findBottomRightClickable()
        if (fallback != null && A11yBridge.click(fallback)) {
            DebugLog.d(TAG, "发送级别 4：右下角兜底 ${fallback.className}")
            return "右下角兜底"
        }

        return null
    }

    /** 右下角（底部 30% / 右侧 35%）里面积最小的可点节点 */
    private fun findBottomRightClickable(): AccessibilityNodeInfo? {
        val svc = A11yBridge.service() ?: return null
        val root = svc.rootInActiveWindow ?: return null
        val metrics = context.resources.displayMetrics
        val minX = metrics.widthPixels * 0.65f
        val minY = metrics.heightPixels * 0.70f

        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (node.isClickable && node.isVisibleToUser &&
                r.left >= minX && r.top >= minY && r.width() in 20..300 && r.height() in 20..300
            ) {
                val area = r.width().toLong() * r.height()
                if (area < bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
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

    /** 找搜索入口：先按描述/文字，再按类名（有些车机版描述是空的） */
    private fun findSearchEntry(): AccessibilityNodeInfo? =
        A11yBridge.findNode(A11yBridge.TextFilter(contentDesc = "搜索"))
            ?: A11yBridge.findNode(A11yBridge.TextFilter(contains = "搜索"))
            ?: A11yBridge.findNode(A11yBridge.TextFilter(exact = "Search"))

    /**
     * 是否停在微信「聊天列表页」。
     * 依据：车机版底部有「通讯录 / 发现 / 我」三个 tab，且没有聊天输入框。
     */
    private fun isOnMainList(): Boolean {
        val hasTab = A11yBridge.findNode(A11yBridge.TextFilter(exact = "通讯录")) != null ||
            A11yBridge.findNode(A11yBridge.TextFilter(exact = "发现")) != null
        return hasTab && !hasEditable()
    }

    /** 当前界面有没有可编辑输入框（= 真的进了搜索或聊天） */
    private fun hasEditable(): Boolean = A11yBridge.allEditables(null).isNotEmpty()

    /**
     * 按布局找车机版微信顶部的放大镜。
     *
     * 依据实测截图：1920x1200 上车机版顶栏的放大镜大约在 (54%, 17%)，
     * 右邻的「+」在 (59%, 17%)。所以先在顶部区域找可点的小图标，优先取最靠近
     * (54%, 17%) 的那个；一个都找不到就按这个比例直接点一次。
     */
    private fun clickSearchIconByLayout(offsetDp: Int = 0): Boolean {
        val svc = A11yBridge.service() ?: return false
        val root = svc.rootInActiveWindow ?: return false
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val targetX = w * 0.537f + offsetDp * metrics.density
        val targetY = h * 0.170f

        var best: AccessibilityNodeInfo? = null
        var bestDist = Float.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            val sizeOk = r.width() in 24..160 && r.height() in 24..160
            val topBand = r.top >= h * 0.08f && r.bottom <= h * 0.28f
            val xOk = r.centerX() >= w * 0.40f && r.centerX() <= w * 0.65f
            if (node.isClickable && node.isVisibleToUser && sizeOk && topBand && xOk) {
                val dx = r.centerX() - targetX
                val dy = r.centerY() - targetY
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }

        val node = best
        if (node != null) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            DebugLog.d(TAG, "车机版放大镜候选 ${node.className} $r，按中心点击")
            if (A11yBridge.click(node)) return true
            if (A11yBridge.clickByGesture(r.centerX(), r.centerY())) return true
        }

        DebugLog.w(TAG, "顶部没找到候选，直接按比例点 (${targetX.toInt()}, ${targetY.toInt()})")
        return A11yBridge.clickByGesture(targetX.toInt(), targetY.toInt())
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
