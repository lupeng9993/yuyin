package com.voiceassistant.model

import com.voiceassistant.log.DebugLog
import java.io.File

/**
 * 根据模型词表把「汉字/拼音」拼成 KWS 关键词行。
 *
 * KWS 的关键词必须是模型词表里的 BPE 片段，不能随便写中文。这里做的是：
 * 1. 读模型 `tokens.txt`，建立「音节 → token」索引；
 * 2. 把用户给的拼音（带声调或不带都行）逐个音节映射成 token；
 * 3. 第一个音节优先用词首符号（▁/▁）版本，然后拼成 `token token ... @显示文本`。
 *
 * 映射不出来的音节会原样报出来，让用户改拼音或手动填关键词行。
 */
object KwsKeywordBuilder {

    private const val TAG = "KwsKeywordBuilder"

    data class Result(val line: String?, val message: String)

    private val toneMap = mapOf(
        'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a',
        'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e',
        'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i',
        'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
        'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u',
        'ǖ' to 'v', 'ǘ' to 'v', 'ǚ' to 'v', 'ǜ' to 'v', 'ü' to 'v'
    )

    private data class Token(val raw: String, val lower: String, val wordStart: Boolean)

    fun build(modelDir: String, display: String, pinyinInput: String): Result {
        val name = display.trim()
        if (name.isEmpty()) return Result(null, "请先填显示文本，例如「你好小助手」")

        val tokens = loadTokens(modelDir)
        if (tokens.isEmpty()) {
            return Result(null, "读不到模型的 tokens.txt，只能手动填关键词行")
        }

        val syllables = pinyinInput.trim()
            .split(' ', '\t', '-', '\'')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (syllables.isEmpty()) return Result(null, "请输入拼音，用空格分开，例如 ni hao xiao zhu shou")

        val picked = ArrayList<String>()
        val missing = ArrayList<String>()
        syllables.forEachIndexed { index, syllable ->
            val token = findToken(tokens, syllable, preferWordStart = index == 0)
            if (token == null) missing.add(syllable) else picked.add(token.raw)
        }

        if (missing.isNotEmpty()) {
            return Result(
                null,
                "这些音节在词表里找不到：${missing.joinToString(" ")}。换个拼法，或改用「手动填关键词行」"
            )
        }

        val line = picked.joinToString(" ") + " @" + name
        DebugLog.d(TAG, "生成关键词行: $line")
        return Result(line, "已生成")
    }

    private fun loadTokens(modelDir: String): List<Token> {
        val file = File(modelDir, "tokens.txt")
        if (!file.exists()) return emptyList()
        return try {
            file.readLines().mapNotNull { line ->
                val raw = line.trim().substringBefore(' ')
                if (raw.isEmpty() || raw == "<blk>" || raw == "<unk>" || raw == "<sos/eos>") return@mapNotNull null
                val stripped = raw.trimStart('▁', '_')
                if (stripped.isEmpty()) return@mapNotNull null
                Token(raw, stripped.lowercase(), raw.startsWith("▁") || raw.startsWith("_"))
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "读取 tokens.txt 失败", e)
            emptyList()
        }
    }

    private fun findToken(tokens: List<Token>, syllable: String, preferWordStart: Boolean): Token? {
        val normalized = stripTone(syllable.lowercase().map { toneMap[it] ?: it }.joinToString(""))
        if (normalized.isEmpty()) return null

        // 1. 完全相等；2. 去声调比较（token 可能带 1–5 的声调数字）
        val exact = tokens.filter { it.lower == syllable.lowercase() }
        val loose = tokens.filter { stripTone(it.lower) == normalized }
        val pool = if (exact.isNotEmpty()) exact else loose
        if (pool.isEmpty()) return null

        return if (preferWordStart) {
            pool.firstOrNull { it.wordStart } ?: pool.first()
        } else {
            pool.firstOrNull { !it.wordStart } ?: pool.first()
        }
    }

    private fun stripTone(s: String): String = s.filter { it !in '1'..'5' }
}
