package com.voiceassistant.util

import java.util.Calendar

/**
 * 中文时间解析：把「8点」「七点半」「晚上9点」「明天早上7点」「19:30」解析成时间戳。
 *
 * 原则：**能解析就解析，解析不了返回 null 让上层反问**，绝不猜。
 */
object TimeParser {

    data class Result(
        val at: Long,
        /** 播报/通知里用的说法，例如「明天 07:30」 */
        val label: String
    )

    private val digits = mapOf(
        '零' to 0, '〇' to 0,
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    /** 中文数字：七 / 十 / 十一 / 二十三 / 三十 */
    private fun chineseToInt(s: String): Int? {
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { return it }

        val idx = s.indexOf('十')
        if (idx < 0) {
            return if (s.length == 1) digits[s[0]] else null
        }
        val tens = if (idx == 0) 1 else (digits[s[0]] ?: return null)
        val ones = if (idx == s.length - 1) 0 else (digits[s[idx + 1]] ?: return null)
        return tens * 10 + ones
    }

    fun parse(text: String, now: Long = System.currentTimeMillis()): Result? {
        var hour: Int? = null
        var minute = 0

        // 19:30 / 19点 / 19点30 / 7点半
        val digitMatch = Regex("(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?").find(text)
        if (digitMatch != null) {
            hour = digitMatch.groupValues[1].toIntOrNull()
            minute = digitMatch.groupValues[2].toIntOrNull() ?: 0
        } else {
            val cnMatch = Regex("([零〇一二两三四五六七八九十]{1,3})\\s*点\\s*(半|(?:[零〇一二两三四五六七八九十]{1,3})分?)?").find(text)
            if (cnMatch != null) {
                hour = chineseToInt(cnMatch.groupValues[1])
                val tail = cnMatch.groupValues[2]
                minute = when {
                    tail == "半" -> 30
                    tail.isNotEmpty() -> chineseToInt(tail.removeSuffix("分")) ?: 0
                    else -> 0
                }
            }
        }

        if (hour == null || hour !in 0..24) return null
        if (minute !in 0..59) return null

        var h = hour

        // 下午/晚上/傍晚 +12（中午12点不转）
        val pm = text.contains("下午") || text.contains("晚上") || text.contains("傍晚") || text.contains("夜里")
        if (pm && h < 12) h += 12
        if ((text.contains("早上") || text.contains("上午") || text.contains("凌晨")) && h == 12) h = 0
        if (h > 23) return null

        // 日期偏移
        val dayOffset = when {
            text.contains("大后天") -> 3
            text.contains("后天") -> 2
            text.contains("明天") || text.contains("明早") || text.contains("明晚") -> 1
            else -> 0
        }

        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 没说"明天"但时间已过 → 顺延到明天（例如下午问"8点提醒我"）
        if (dayOffset == 0 && cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val target = cal.timeInMillis
        val labelCal = Calendar.getInstance().apply { timeInMillis = target }
        val todayCal = Calendar.getInstance().apply { timeInMillis = now }
        val sameDay = labelCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            labelCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)

        val hm = "%02d:%02d".format(
            labelCal.get(Calendar.HOUR_OF_DAY),
            labelCal.get(Calendar.MINUTE)
        )
        val dayLabel = when {
            sameDay -> "今天"
            isTomorrow(todayCal, labelCal) -> "明天"
            else -> "${labelCal.get(Calendar.MONTH) + 1}月${labelCal.get(Calendar.DAY_OF_MONTH)}日"
        }

        return Result(target, "$dayLabel $hm")
    }

    private fun isTomorrow(today: Calendar, target: Calendar): Boolean {
        val c = Calendar.getInstance().apply {
            timeInMillis = today.timeInMillis
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    }
}
