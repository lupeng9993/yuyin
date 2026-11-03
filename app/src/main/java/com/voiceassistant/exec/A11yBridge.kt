package com.voiceassistant.exec

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.voiceassistant.log.DebugLog
import java.lang.ref.WeakReference

/**
 * 无障碍能力网关：给执行器提供「等窗口 → 找节点 → 点击 → 输入 → 截屏」这层能力。
 *
 * 所有操作都带超时和容错——微信界面千变万化，找不到就返回失败，绝不无限等待。
 */
object A11yBridge {

    private const val TAG = "A11yBridge"

    @Volatile
    private var serviceRef: WeakReference<VoiceAccessibilityService>? = null

    @Volatile
    private var currentPackage: String = ""

    fun attach(service: VoiceAccessibilityService) {
        serviceRef = WeakReference(service)
        DebugLog.d(TAG, "无障碍服务已连接")
    }

    fun detach() {
        serviceRef = null
        DebugLog.w(TAG, "无障碍服务已断开")
    }

    fun service(): AccessibilityService? = serviceRef?.get()?.takeIf { it.isReady() }

    fun isReady(): Boolean = service() != null

    fun onWindowChanged(event: AccessibilityEvent) {
        event.packageName?.toString()?.let { currentPackage = it }
    }

    fun currentWindowPackage(): String = currentPackage

    // ---------------- 节点查找 ----------------

    data class TextFilter(
        val contains: String? = null,
        val exact: String? = null,
        val viewIdSuffix: String? = null,
        val contentDesc: String? = null
    )

    private fun filterMatches(node: AccessibilityNodeInfo, f: TextFilter): Boolean {
        if (f.viewIdSuffix != null) {
            val id = node.viewIdResourceName ?: return false
            if (!id.endsWith(f.viewIdSuffix)) return false
        }
        if (f.exact != null) {
            val text = node.text?.toString()
            if (text != f.exact) return false
        }
        if (f.contains != null) {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if ((text == null || !text.contains(f.contains)) && (desc == null || !desc.contains(f.contains))) return false
        }
        if (f.contentDesc != null) {
            if (node.contentDescription?.toString() != f.contentDesc) return false
        }
        return true
    }

    /**
     * 所有可能的窗口根节点。
     *
     * 长按输入框弹出的「粘贴/全选」菜单通常是一个**独立窗口**，
     * 只搜 rootInActiveWindow 是找不到它的——这很可能就是菜单粘贴失败的原因。
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val svc = service() ?: return emptyList()
        val list = ArrayList<AccessibilityNodeInfo>(4)
        svc.rootInActiveWindow?.let { list.add(it) }
        try {
            svc.windows?.forEach { w ->
                w?.root?.let { r -> if (list.none { it == r }) list.add(r) }
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "枚举窗口失败: ${e.message}")
        }
        return list
    }

    /** 广度优先在整棵窗口树里找第一个匹配节点（栈式遍历，深度上限 40 层） */
    fun findNode(filter: TextFilter, root: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val starts = if (root != null) listOf(root) else roots()
        if (starts.isEmpty()) return null
        for (start in starts) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(start)
            var visited = 0
            while (queue.isNotEmpty() && visited < 800) {
                val node = queue.removeFirst()
                visited++
                if (filterMatches(node, filter)) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }

    fun findAll(filter: TextFilter, root: AccessibilityNodeInfo? = null, limit: Int = 8): List<AccessibilityNodeInfo> {
        val start = root ?: service()?.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(start)
        var visited = 0
        while (queue.isNotEmpty() && visited < 1200 && out.size < limit) {
            val node = queue.removeFirst()
            visited++
            if (filterMatches(node, filter)) out.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return out
    }

    // ---------------- 动作 ----------------

    fun click(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
        }
        // 退而求其次：点它的中心点
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return clickByGesture(rect.centerX(), rect.centerY())
        }
        return false
    }

    fun clickByGesture(x: Int, y: Int): Boolean {
        val svc = service() ?: return false
        return try {
            val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            svc.dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            DebugLog.e(TAG, "手势点击失败", e)
            false
        }
    }

    /**
     * 输入文本。
     *
     * 踩过的坑：微信的输入框对 ACTION_SET_TEXT 返回 true，**但内容根本没写进去**
     * （只有真实输入法事件才会触发它的 TextWatcher）。所以每一步都必须回读校验，
     * 失败就换「剪贴板 + ACTION_PASTE」。
     */
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        // 已经写进去了就别再写一遍（重复写入会导致微信里出现两份文字）
        if (nodeHasText(node, text)) {
            DebugLog.d(TAG, "输入框已有这段文字，跳过写入")
            return true
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        /*
         * 这里**只信 performAction 的返回值**，不再拿回读校验当门槛。
         *
         * 教训：微信的输入框 SET_TEXT 其实生效了，但节点 text 属性不更新（或文字挂在子节点），
         * 回读校验就会误判为失败，于是又贴一遍 → 变成两份内容。
         * 只有 SET_TEXT 明确返回 false 时才降级到粘贴。
         */
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            DebugLog.d(TAG, "SET_TEXT 已提交（不强行回读，微信节点文本会滞后）")
            return true
        }
        DebugLog.w(TAG, "SET_TEXT 被拒绝，改用剪贴板粘贴")

        if (pasteDirect(node, text)) {
            DebugLog.d(TAG, "ACTION_PASTE 已提交")
            // 粘贴完清空剪贴板，免得下次粘到旧内容
            clearClipboard()
            return true
        }
        DebugLog.w(TAG, "ACTION_PASTE 被拒绝，改用长按菜单粘贴")

        if (pasteViaMenu(node, text)) {
            clearClipboard()
            return true
        }

        DebugLog.w(TAG, "三种输入方式都没写进去")
        return false
    }

    /** 用完清空剪贴板 */
    fun clearClipboard() {        try {
            val svc = service() ?: return
            val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 直接 ACTION_PASTE */
    private fun pasteDirect(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val svc = service() ?: return false
            val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("va", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            DebugLog.e(TAG, "粘贴失败", e)
            false
        }
    }

    /**
     * 长按输入框 → 系统菜单里点「粘贴」。
     * 微信这类自绘输入框只认真实输入法事件，ACTION_PASTE 会被忽略，
     * 但菜单里的「粘贴」是普通可点节点，无障碍点得动。
     */
    private fun pasteViaMenu(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val svc = service() ?: return false
            val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("va", text))

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            android.os.SystemClock.sleep(500)

            val paste = findNode(TextFilter(exact = "粘贴"))
                ?: findNode(TextFilter(contains = "粘贴"))
                ?: findNode(TextFilter(exact = "Paste"))
                ?: findNode(TextFilter(contains = "Paste"))
            if (paste == null) {
                DebugLog.w(TAG, "长按后没找到「粘贴」菜单")
                return false
            }
            DebugLog.d(TAG, "点击菜单里的「粘贴」")
            click(paste)
        } catch (e: Exception) {
            DebugLog.e(TAG, "菜单粘贴失败", e)
            false
        }
    }

    /** 节点（或它的直接子节点）里是否已经包含这段文字 */
    fun nodeHasText(node: AccessibilityNodeInfo, expected: String): Boolean {
        val target = expected.trim()
        if (target.isEmpty()) return true
        if (node.text?.toString()?.contains(target) == true) return true
        if (node.contentDescription?.toString()?.contains(target) == true) return true
        val childCount = minOf(node.childCount, 6)
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (child.text?.toString()?.contains(target) == true) return true
            if (child.contentDescription?.toString()?.contains(target) == true) return true
        }
        return false
    }

    /** 回读输入框内容确认真的写进去了（有些输入法/界面有延迟，重试几次） */
    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean {
        val target = expected.trim()
        if (target.isEmpty()) return true
        repeat(8) {
            if (nodeHasText(node, target)) return true
            android.os.SystemClock.sleep(60)
        }
        return false
    }

    /**
     * 等价于按键盘上的「发送/前往」键（Android 11+）。
     * 比去点输入法窗口里的具体键位可靠得多。
     */
    fun imeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        } catch (e: Exception) {
            DebugLog.w(TAG, "IME_ENTER 失败: ${e.message}")
            false
        }
    }

    /** 把当前界面的节点树写进日志，用来适配车机版/不同 ROM 的界面 */
    fun dumpTree(tag: String = "NodeDump") {
        val svc = service() ?: run {
            DebugLog.w(tag, "无障碍未连接，导不出节点树")
            return
        }
        DebugLog.d(tag, "===== 开始导出节点树 =====")
        // 逐窗口导出并打印窗口类型：root 为 null 时能区分"敏感窗口被屏蔽"还是"真的没内容"
        val windows = try {
            svc.windows?.toList().orEmpty()
        } catch (e: Exception) {
            DebugLog.w(tag, "枚举窗口失败: ${e.message}")
            emptyList()
        }
        if (windows.isEmpty()) DebugLog.w(tag, "没有可用窗口")

        windows.forEachIndexed { index, w ->
            val root = try {
                w.root
            } catch (e: Exception) {
                null
            }
            val typeName = try {
                when (w.type) {
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法"
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "系统"
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍浮层"
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "应用"
                    else -> "其他(${w.type})"
                }
            } catch (e: Exception) {
                "?"
            }
            val active = try {
                w.isActive
            } catch (e: Exception) {
                false
            }
            DebugLog.d(
                tag,
                "[窗口 $index] pkg=${root?.packageName ?: "?"} cls=${root?.className ?: "null"} " +
                    "type=$typeName active=$active rootNull=${root == null}"
            )
            if (root == null) return@forEachIndexed

            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (visited++ > 400 || depth > 30) return
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                val text = node.text?.toString()?.take(30).orEmpty()
                val desc = node.contentDescription?.toString()?.take(30).orEmpty()
                if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
                    DebugLog.d(
                        tag,
                        "  ".repeat(depth) + "${node.className} [$r] " +
                            "text='$text' desc='$desc' " +
                            "click=${node.isClickable} edit=${node.isEditable} focus=${node.isFocused}"
                    )
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it, depth + 1) }
                }
            }
            walk(root, 0)
        }
        DebugLog.d(tag, "===== 节点树导出结束 =====")
    }

    fun back(): Boolean = service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false    fun home(): Boolean = service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** 当前所有可编辑节点（按面积从大到小），用于「挨个试到能写进去为止」 */
    fun allEditables(exclude: AccessibilityNodeInfo? = null): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        for (start in roots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(start)
            var visited = 0
            while (queue.isNotEmpty() && visited < 700) {
                val node = queue.removeFirst()
                visited++
                val excluded = exclude != null && node == exclude
                if (!excluded && node.isEditable && node.isVisibleToUser) result.add(node)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        // 同一个 EditText 可能出现在多个窗口里，按「类名 + 位置」去重；
        // 面积大的更可能是真正的主输入框
        return result
            .distinctBy { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                "${n.className}#${r.left},${r.top},${r.right},${r.bottom}"
            }
            .sortedByDescending { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                r.width().toLong() * r.height()
            }
    }

    // ---------------- 截屏（API 30+ 无障碍自带能力，不需要额外授权） ----------------

    fun canTakeScreenshot(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun takeScreenshot(callback: (Bitmap?) -> Unit) {
        val svc = service()
        if (svc == null || !canTakeScreenshot()) {
            callback(null)
            return
        }
        try {
            svc.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                { Runnable { } },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        callback(bmp?.copy(Bitmap.Config.ARGB_8888, false))
                    }

                    override fun onFailure(errorCode: Int) {
                        DebugLog.w(TAG, "截屏失败 code=$errorCode")
                        callback(null)
                    }
                }
            )
        } catch (e: Exception) {
            DebugLog.e(TAG, "截屏异常", e)
            callback(null)
        }
    }
}
