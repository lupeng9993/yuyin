package com.voiceassistant.nlu

/**
 * 本地规则匹配（第一优先级）。纯离线、无依赖、毫秒级。
 *
 * 设计要点：
 * 1. 整句优先：整句命中就直接产出单个动作，避免把「给张三发微信说，我四十分钟到」
 *    里的逗号当成动作分隔符，把消息正文切坏。
 * 2. 整句不中才按子句切分（逗号/句号/然后/再/接着），逐个子句匹配。
 * 3. 最多 [ActionPlan.MAX_ACTIONS] 个子句，超出部分忽略并记一条告警。
 */
object RuleEngine {

    private const val MAX_CLAUSES = ActionPlan.MAX_ACTIONS

    // ---- 整句级模板 ----

    /** 给某某发微信说……（消息正文允许包含逗号，所以整句匹配） */
    private val wechat = Regex(
        "^(?:帮我|请|麻烦)?\\s*(?:给|向)\\s*(.+?)\\s*" +
            "(?:发|发送|发条|发个|发一条|转告)\\s*(?:个|一条)?\\s*" +
            "(?:微信|wechat)(?:消息)?\\s*[，,：:]?\\s*" +
            "(?:说|内容是|告诉他|告诉她|跟他说|跟她说)?\\s*[，,：:]?\\s*(.*)$",
        RegexOption.IGNORE_CASE
    )

    /** 导航到某某 / 带我去某某 / 去某某 */
    private val navigate = Regex(
        "^(?:帮我|请|麻烦)?\\s*(?:导航(?:到|去)?|带我去|领我去|我要去|我想去|去)\\s*(.+?)" +
            "\\s*(?:吧|呀|啊)?\\s*[。.!！]?$"
    )

    /** 系统 / 应用控制类，整句捕获后交给系统执行器细分 */
    private val systemControl = Regex(
        "^(?:帮我|请|麻烦)?\\s*(?:" +
            "回到|返回|显示|回|" +
            "打开|开启|启动|关闭|退出|" +
            "调高|调低|调大|调小|提高|降低|设置|调到|设为|" +
            "静音|取消静音|截屏|截图|屏幕截图|" +
            "暂停|暂停播放|继续播放|播放|停止播放|上一首|下一首|切歌" +
            ").*$"
    )

    private val volumeWord = Regex("^(?:把)?音量.*$")

    /** 口语里常见的前缀噪音，匹配前先剥掉：「给我打开抖音」「嗯，那个打开设置」 */
    private val fillerPrefix = Regex(
        "^(?:嗯+|那个|就是|帮我|帮我给|给我|帮忙|请|麻烦|你|能|能不能|可以|可不可以|我想|我要)+"
    )

    /** 「把抖音打开」这种倒装句（只处理打开类动词，关闭类不做也别乱猜） */
    private val baForm = Regex("^把(.{1,20}?)(打开|开启|启动|运行)$")

    /** 宽松兜底：句子中间出现「打开/启动/开启」也算系统控制 */
    private val looseOpen = Regex("(打开|开启|启动|运行)\\s*(.{1,20})$")

    data class Match(val plan: ActionPlan, val truncated: Int = 0)

    /**
     * @return 未命中任何模板时返回 null（交给云端或提示失败）
     */
    fun matchLocal(text: String): Match? {
        val sentence = normalize(text)
        if (sentence.isEmpty()) return null

        // 1. 整句优先：微信 / 导航。这两类的正文里可能自带逗号，先按整句吃下来，
        //    避免「给我妈发微信说，我晚点回」里的逗号把消息正文切断。
        matchWechat(sentence)?.let { return Match(ActionPlan.single(it, ActionPlan.Source.LOCAL_RULE)) }
        matchNavigate(sentence)?.let { return Match(ActionPlan.single(it, ActionPlan.Source.LOCAL_RULE)) }

        // 2. 其余情况按子句切分，逐句匹配（一句话多个动作靠这一步）
        val clauses = splitClauses(sentence)
        val matched = clauses.mapNotNull { matchClause(it) }
        if (matched.isEmpty()) return null

        val actions = matched.take(MAX_CLAUSES)
        val truncated = matched.size - actions.size
        return Match(ActionPlan(actions, ActionPlan.Source.LOCAL_RULE), truncated)
    }

    /** 单个子句的匹配顺序：微信 → 导航 → 系统控制 */
    private fun matchClause(clause: String): VaAction? =
        matchWechat(clause) ?: matchNavigate(clause) ?: matchSystem(clause)

    private fun matchWechat(clause: String): VaAction? {
        val m = wechat.find(clause) ?: return null
        val contact = m.groupValues[1].trim()
        val message = m.groupValues[2].trim().trimEnd('。', '！', '!', '.')
        return when {
            contact.isEmpty() -> VaAction.unknown("缺少联系人")
            message.isEmpty() -> VaAction.unknown("缺少消息内容")
            else -> VaAction(ActionType.SEND_WECHAT, contact = contact, message = message)
        }
    }

    private fun matchNavigate(clause: String): VaAction? {
        val m = navigate.find(clause) ?: return null
        val dest = m.groupValues[1].trim()
        if (dest.isEmpty() || dest.contains("微信")) return null
        return VaAction(ActionType.NAVIGATE, destination = dest)
    }

    private fun matchSystem(clause: String): VaAction? {
        // 1. 先剥掉口语前缀噪音再匹配
        val stripped = clause.replace(fillerPrefix, "").trim()
        val target = if (stripped.isEmpty()) clause else stripped

        if (systemControl.matches(target) || volumeWord.matches(target)) {
            return VaAction(ActionType.OTHER, rawText = target)
        }

        // 2. 倒装：「把抖音打开」
        baForm.find(target)?.let { m ->
            val what = m.groupValues[1].trim()
            if (what.isNotEmpty()) {
                return VaAction(ActionType.OTHER, rawText = "打开$what")
            }
        }

        // 3. 宽松兜底：句中含「打开/启动/开启 + 内容」
        looseOpen.find(target)?.let { m ->
            val what = m.groupValues[2].trim()
            if (what.isNotEmpty()) {
                return VaAction(ActionType.OTHER, rawText = "打开$what")
            }
        }

        return null
    }

    /**
     * 子句切分：先按标点切，再按连接词切。
     * 「然后」「接着」「再」这类连接词切开后要去掉，避免污染指令。
     */
    fun splitClauses(text: String): List<String> {
        val byPunct = text.split('，', ',', '。', '；', ';', '！', '!', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val result = ArrayList<String>()
        for (part in byPunct) {
            val pieces = part.split("然后", "接着", "之后再", "再帮我", "再")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            result.addAll(pieces)
        }
        return result
    }

    /** 全角转半角、去首尾空白、统一数字写法 */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch == '\u3000' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim().trimEnd('。', '！', '!', '.')
    }
}
