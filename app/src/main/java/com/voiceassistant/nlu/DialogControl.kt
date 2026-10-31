package com.voiceassistant.nlu

/**
 * 会话控制：识别「结束对话」类说法。
 *
 * 这类话不做任何执行，只回一句告别然后继续待命。
 * 判定用「整句匹配」，避免把「退出微信」这种半截指令误判成结束对话。
 */
object DialogControl {

    private val endPhrases = setOf(
        "再见", "拜拜", "88", "byebye", "bye",
        "结束对话", "退出对话", "关闭对话", "结束会话", "退出会话",
        "不聊了", "没事了", "不用了", "没有了", "就这样", "先这样",
        "退下吧", "退下", "好了谢谢", "谢谢没事了", "安静吧"
    )

    private val trailing = charArrayOf('。', '！', '!', '？', '?', '，', ',', ' ', '~', '～', '吧', '呀', '啊', '啦')

    fun isEndOfConversation(rawText: String): Boolean {
        var text = RuleEngine.normalize(rawText)
        text = text.trim(*trailing)
        if (text.isEmpty()) return false
        return endPhrases.contains(text.lowercase())
    }
}
