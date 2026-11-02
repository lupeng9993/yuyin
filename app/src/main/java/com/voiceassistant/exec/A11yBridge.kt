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

    /** 广度优先在整棵窗口树里找第一个匹配节点（栈式遍历，深度上限 40 层） */
    fun findNode(filter: TextFilter, root: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        val start = root ?: service()?.rootInActiveWindow ?: return null
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
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (verifyText(node, text)) return true
        DebugLog.w(TAG, "SET_TEXT 没生效，改用剪贴板粘贴")

        // 粘贴兜底
        val pasted = try {
            val svc = service() ?: return false
            val cm = svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("va", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            verifyText(node, text)
        } catch (e: Exception) {
            DebugLog.e(TAG, "粘贴文本失败", e)
            false
        }
        if (!pasted) DebugLog.w(TAG, "粘贴也没生效（输入框仍是空的）")
        return pasted
    }

    /** 回读输入框内容确认真的写进去了（有些输入法/界面有延迟，重试几次） */
    private fun verifyText(node: AccessibilityNodeInfo, expected: String): Boolean {
        val target = expected.trim()
        if (target.isEmpty()) return true
        repeat(8) {
            val current = node.text?.toString().orEmpty()
            if (current.contains(target)) return true
            android.os.SystemClock.sleep(60)
        }
        return false
    }

    fun back(): Boolean = service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    fun home(): Boolean = service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

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
