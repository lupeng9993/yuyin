package com.voiceassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 中文时间解析测试。
 * 所有用例都传入固定的「现在」，避免跟着系统时间飘。
 */
class TimeParserTest {

    /** 固定参考时间：2026-11-02 10:00（周一） */
    private val now = at(2026, 11, 2, 10, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `阿拉伯数字加点半`() {
        // 现在 10:00，说「7点半」→ 已过点，顺延到明天 07:30
        val r = TimeParser.parse("7点半提醒我交电费", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 3, 7, 30), r!!.at)
    }

    @Test
    fun `中文数字加点半`() {
        // 06:00 时说「七点半」→ 今天 07:30
        val morning = at(2026, 11, 2, 6, 0)
        val r = TimeParser.parse("七点半提醒我", morning)
        assertNotNull(r)
        assertEquals(at(2026, 11, 2, 7, 30), r!!.at)
    }

    @Test
    fun `明天早上`() {
        val r = TimeParser.parse("明天早上7点半提醒我交电费", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 3, 7, 30), r!!.at)
    }

    @Test
    fun `晚上自动加十二小时`() {
        val r = TimeParser.parse("晚上9点提醒我吃药", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 2, 21, 0), r!!.at)
    }

    @Test
    fun `二十四小时制`() {
        val r = TimeParser.parse("19:30提醒我", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 2, 19, 30), r!!.at)
    }

    @Test
    fun `点钟分钟`() {
        val r = TimeParser.parse("下午3点15分提醒我开会", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 2, 15, 15), r!!.at)
    }

    @Test
    fun `已经过去的时间顺延到明天`() {
        // 现在是 10:00，说「8点」→ 明天 08:00
        val r = TimeParser.parse("8点提醒我开会", now)
        assertNotNull(r)
        assertEquals(at(2026, 11, 3, 8, 0), r!!.at)
    }

    @Test
    fun `没有时间就返回空`() {
        assertNull(TimeParser.parse("提醒我开会", now))
        assertNull(TimeParser.parse("随便说点什么", now))
    }

    @Test
    fun `标签能看懂`() {
        val today = TimeParser.parse("晚上8点提醒我", now)
        assertEquals("今天 20:00", today!!.label)

        val tomorrow = TimeParser.parse("明天8点提醒我", now)
        assertEquals("明天 08:00", tomorrow!!.label)
    }
}
