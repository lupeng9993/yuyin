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
    private val offsetDp: () -> Float = { 8f },
    /** 播报一个问题并听一句自由内容；返回 null 表示没听到 */
    private val listen: suspend (String, Long) -> String? = { _, _ -> null }
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

        // 优先走「截图 + OCR + shell 点击」：车机版微信自绘界面，无障碍读不到节点
        val ocrResult = OcrWechatFlow(context).send(
            contact = contact,
            message = message,
            choose = choose,
            listen = listen
        )
        if (ocrResult != null) {
            DebugLog.d(TAG, "OCR 路径结果 ok=${ocrResult.ok} ${ocrResult.detail}")
            return@withContext ActionResult(action, ocrResult.ok, ocrResult.speech, ocrResult.detail)
        }
        DebugLog.d(TAG, "OCR 路径不适用，回退无障碍路径")

        // 1. 把微信拉到前台并等它真正显示（车机版启动慢，等够时间）
        relaunchWechat()
        waitForPackage(WECHAT_PKG)

        // 2. 进搜索：不依赖"找到搜索入口节点"，直接按坐标点放大镜，
        //    用「右栏出现输入框」作为成功判据；按返回前必须确认在聊天页。
        var entered = false
        var backs = 0
        for (attempt in 0..2) {
            if (!inWechatWindow()) {
                DebugLog.w(TAG, "不在微信（${A11yBridge.currentWindowPackage()}），重新拉起并等待")
                relaunchWechat()
                waitForPackage(WECHAT_PKG)
                continue
            }

            // 停在聊天页（有输入框且没有列表 tab）→ 只按一次返回，等够 2 秒
            if (hasEditable() && !isOnMainList() && backs < 2) {
                backs++
                DebugLog.d(TAG, "看起来在聊天页，按返回回列表（第 $backs 次）")
                A11yBridge.back()
                delay(2000)
                continue
            }

            for (offset in listOf(0, -16, 16)) {
                DebugLog.w(TAG, "按坐标点放大镜（偏移 $offset dp）")
                if (clickSearchIconByLayout(offset)) {
                    delay(1500)
                    if (hasEditable()) {
                        entered = true
                        DebugLog.d(TAG, "已进入微信搜索页（偏移 $offset dp）")
                        break
                    }
                }
            }
            if (entered) break
        }

        if (!entered) {
            A11yBridge.dumpTree("NodeDump")
            return@withContext fail(
                action,
                "没能进入微信搜索",
                "按坐标点放大镜后仍未出现输入框（已导出节点树）"
            )
        }

        // 2. 输入联系人：车机版搜索框在**右栏顶部**，左栏是聊天列表，不能写左栏
        val searchInput = clearAndType(contact, exclude = null)
            ?: return@withContext fail(action, "搜索框写不进文字", "右栏没有可写入的输入框")
        delay(1200)

        // 3. 结果只在右栏找
        val candidates = awaitNodes("联系人结果", 3000L) { findResults(contact, searchInput) }
        if (candidates.isEmpty()) {
            A11yBridge.back()
            return@withContext fail(action, "未找到联系人「$contact」，消息未发送", "搜索结果为空")
        }

        // 4. 完全匹配且唯一 → 直接进；否则问一句 / 问第几个
        val exact = candidates.filter { it.first.trim() == contact.trim() }
        val pickedIndex: Int = when {
            exact.size == 1 -> candidates.indexOf(exact.first())

            candidates.size == 1 -> {
                val only = candidates[0].first
                if (!confirm("找到「$only」，是要发给TA吗？")) {
                    A11yBridge.back()
                    return@withContext ActionResult(action, false, "已取消", "包含匹配被拒绝")
                }
                0
            }

            else -> {
                val names = candidates.map { it.first }
                val idx = choose(names)
                if (idx == null || idx !in candidates.indices) {
                    A11yBridge.back()
                    return@withContext fail(
                        action,
                        "有 ${names.size} 个匹配，没有选择，已取消",
                        "候选取消: $names"
                    )
                }
                idx
            }
        }
        val matchedName = candidates[pickedIndex].first
        val resultNode = candidates[pickedIndex].second

        if (!A11yBridge.click(resultNode)) {
            return@withContext fail(action, "点不开联系人「$matchedName」", "点击失败")
        }
        delay(1200)

        // 5. 聊天输入框（右栏底部）写入初始内容
        var body = message
        val chatInput = clearAndType(body, exclude = searchInput)
            ?: return@withContext fail(
                action,
                "没进到和「$matchedName」的聊天界面，或消息写不进去",
                "点击后没有可写入的聊天输入框"
            )
        delay(600)

        // 6. 多轮追加：最多 3 轮，6 秒静默就追问一次
        var rounds = 0
        var sendNow = false
        while (rounds < 3 && !sendNow) {
            rounds++
            val answer = listen("还有要添加的吗？没有就说发送", 6_000)
            if (answer == null) {
                val again = listen("要发送吗", 6_000)
                if (again == null || !isAffirmative(again)) {
                    // 不发送：退出聊天并回桌面
                    A11yBridge.back()
                    delay(600)
                    A11yBridge.home()
                    DebugLog.d(TAG, "两轮都没回应，取消发送并回桌面")
                    return@withContext ActionResult(action, false, "没有发送，已退出", "超时取消")
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
                if (clearAndType(body, exclude = searchInput) == null) {
                    DebugLog.w(TAG, "追加内容写入失败")
                }
                delay(400)
            }
        }
        if (!sendNow) DebugLog.d(TAG, "追加 $rounds 轮后直接发送")

        // 7. 点发送
        val sendLevel = doSend(chatInput)
        if (sendLevel == null) {
            return@withContext fail(
                action,
                "找不到发送按钮，消息留在输入框里了",
                "四级发送全部失败"
            )
        }
        delay(1200)

        // 8. 校验：右栏里出现这条消息才算真发出去
        val appeared = messageAppears(body)
        DebugLog.d(TAG, "发送复核 appeared=$appeared send=$sendLevel")
        return@withContext ActionResult(
            action,
            appeared,
            if (appeared) "已发送给$matchedName" else "点了发送，但没看到消息，请确认一下",
            "contact=$matchedName send=$sendLevel appeared=$appeared rounds=$rounds"
        )
    }

    /** 「没有」「发送」「发送吧」「说了发送」——句末才算发送指令 */
    private fun wantsSend(text: String): Boolean {
        val t = text.trim().trimEnd('。', '.', '，', ',', '!', '！')
        if (t == "没有" || t == "不用了" || t == "没有了") return true
        return t.endsWith("发送") || t.endsWith("发送吧") || t.endsWith("发出去") ||
            t.endsWith("确认发送") || t == "发"
    }

    private fun isAffirmative(text: String): Boolean {
        val t = text.trim()
        if (wantsSend(t)) return true
        return t.contains("是") || t.contains("好") || t.contains("嗯") || t.contains("对") || t.contains("发")
    }

    /**
     * 发完之后右栏里能看到这条消息吗。
     *
     * 必须**排除可编辑节点**：发送失败时文字还留在输入框里，
     * 如果连输入框一起搜，会把"没发出去"误判成"已发送"。
     */
    private fun messageAppears(text: String): Boolean {
        val key = text.trim().take(12)
        if (key.isEmpty()) return false
        val svc = A11yBridge.service() ?: return false
        val root = svc.rootInActiveWindow ?: return false

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 900) {
            val node = queue.removeFirst()
            visited++
            if (!node.isEditable && node.isVisibleToUser) {
                val label = node.text?.toString().orEmpty()
                if (label.contains(key)) return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    /** 只在右栏（x > 屏宽/2）找可编辑节点，先清空再写入 */
    private suspend fun clearAndType(text: String, exclude: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val metrics = context.resources.displayMetrics
        val half = metrics.widthPixels / 2
        val rightSide = A11yBridge.allEditables(exclude).filter { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            r.centerX() > half
        }
        val targets = rightSide.ifEmpty { A11yBridge.allEditables(exclude) }

        for (node in targets) {
            if (A11yBridge.nodeHasText(node, text)) return node
            clearNode(node)
            if (A11yBridge.setText(node, text)) return node
        }
        return null
    }

    /** 清掉搜索框里上次留下的字 */
    private fun clearNode(node: AccessibilityNodeInfo) {
        try {
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    ""
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            // 没清掉也不影响写新内容
        }
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
     *
     * 只看底部 tab（通讯录/发现/我），**不能**再要求"没有输入框"——
     * 车机版列表页顶部常驻一个可编辑的搜索框，之前那个条件让它永远判定为"不在列表页"，
     * 于是连按返回把微信退到了后台。
     */
    private fun isOnMainList(): Boolean {
        return A11yBridge.findNode(A11yBridge.TextFilter(exact = "通讯录")) != null ||
            A11yBridge.findNode(A11yBridge.TextFilter(exact = "发现")) != null ||
            A11yBridge.findNode(A11yBridge.TextFilter(exact = "我")) != null
    }

    /** 当前前台是不是微信 */
    private fun inWechatWindow(): Boolean =
        A11yBridge.currentWindowPackage() == WECHAT_PKG

    /** 重新把微信拉到前台 */
    private fun relaunchWechat() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(WECHAT_PKG)
                ?: return
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            context.startActivity(intent)
        } catch (e: Exception) {
            DebugLog.w(TAG, "重新拉起微信失败: ${e.message}")
        }
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
        val targetX = w * 0.43f + offsetDp * metrics.density
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
            val xOk = r.centerX() >= w * 0.28f && r.centerX() <= w * 0.52f
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
