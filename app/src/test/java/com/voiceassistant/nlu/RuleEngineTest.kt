package com.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地规则引擎的单元测试：纯 Kotlin，不依赖 Android，CI 里跑 testDebugUnitTest。
 */
class RuleEngineTest {

    @Test
    fun `导航整句命中`() {
        val match = RuleEngine.matchLocal("导航到济南西站")
        assertNotNull(match)
        val action = match!!.plan.actions.single()
        assertEquals(ActionType.NAVIGATE, action.type)
        assertEquals("济南西站", action.destination)
    }

    @Test
    fun `带我去句式`() {
        val match = RuleEngine.matchLocal("带我去大明湖景区吧")
        assertNotNull(match)
        assertEquals("大明湖景区", match!!.plan.actions.single().destination)
    }

    @Test
    fun `微信消息里的逗号不能被当成动作分隔`() {
        val match = RuleEngine.matchLocal("给张三发微信说，我四十分钟到，不用等我吃饭。")
        assertNotNull(match)
        val actions = match!!.plan.actions
        assertEquals(1, actions.size)
        val action = actions.single()
        assertEquals(ActionType.SEND_WECHAT, action.type)
        assertEquals("张三", action.contact)
        assertTrue(action.message.contains("我四十分钟到"))
        assertTrue(action.message.contains("不用等我吃饭"))
    }

    @Test
    fun `一句话两个动作按顺序拆开`() {
        val match = RuleEngine.matchLocal("打开网易云，音量调小一点")
        assertNotNull(match)
        val actions = match!!.plan.actions
        assertEquals(2, actions.size)
        assertEquals(ActionType.OTHER, actions[0].type)
        assertTrue(actions[0].rawText.contains("打开网易云"))
        assertEquals(ActionType.OTHER, actions[1].type)
        assertTrue(actions[1].rawText.contains("音量"))
    }

    @Test
    fun `连接词也能切分`() {
        val clauses = RuleEngine.splitClauses("打开音乐然后调大音量")
        assertEquals(listOf("打开音乐", "调大音量"), clauses)
    }

    @Test
    fun `无关句子不命中`() {
        assertNull(RuleEngine.matchLocal("今天天气怎么样"))
    }

    @Test
    fun `全角数字与标点被归一化`() {
        val normalized = RuleEngine.normalize("音量调到３０％。")
        assertTrue(normalized.contains("30"))
        assertTrue(!normalized.endsWith("。"))
    }

    @Test
    fun `超过五个动作时截断`() {
        val sentence = (1..7).joinToString("，") { "打开应用$it" }
        val match = RuleEngine.matchLocal(sentence)
        assertNotNull(match)
        assertEquals(ActionPlan.MAX_ACTIONS, match!!.plan.actions.size)
        assertTrue(match.truncated > 0)
    }
}
