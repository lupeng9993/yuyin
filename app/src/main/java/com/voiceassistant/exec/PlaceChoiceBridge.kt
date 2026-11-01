package com.voiceassistant.exec

import android.content.Context
import android.content.Intent
import com.voiceassistant.log.DebugLog
import com.voiceassistant.ui.PlaceChoiceActivity
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 导航候选选择桥：服务把候选列表交给界面，界面点选后把序号回传。
 *
 * 用界面选而不是纯语音，是因为地名同音字多、序号念错更麻烦；
 * 界面起不来（后台启动限制）时自动退回语音选择。
 */
object PlaceChoiceBridge {

    private const val TAG = "PlaceChoice"

    data class Request(val places: List<NavigationExecutor.Place>)

    @Volatile
    var current: Request? = null
        private set

    /** 界面是否真的弹出来了：false 时才需要退回语音选择 */
    @Volatile
    var startedOk: Boolean = false
        private set

    /** 语音已经选好了，通知界面自己关掉 */
    @Volatile
    var closeRequested: Boolean = false
        private set

    fun requestClose() {
        closeRequested = true
    }

    fun clearClose() {
        closeRequested = false
    }

    private var continuation: CancellableContinuation<Int?>? = null

    /** 弹出选择界面并等待结果；返回 null 表示取消或超时 */
    suspend fun ask(context: Context, places: List<NavigationExecutor.Place>): Int? =
        suspendCancellableCoroutine { cont ->
            current = Request(places)
            continuation = cont

            val intent = Intent(context, PlaceChoiceActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            try {
                context.startActivity(intent)
                startedOk = true
                DebugLog.d(TAG, "已弹出候选选择界面（${places.size} 项）")
            } catch (e: Exception) {
                startedOk = false
                DebugLog.w(TAG, "无法弹出选择界面，退回语音选择: ${e.message}")
                current = null
                continuation = null
                if (cont.isActive) cont.resume(null)
            }

            cont.invokeOnCancellation {
                current = null
                continuation = null
            }
        }

    /** 界面点击后调用 */
    fun answer(index: Int?) {
        val cont = continuation
        continuation = null
        current = null
        if (cont != null && cont.isActive) cont.resume(index)
    }
}
