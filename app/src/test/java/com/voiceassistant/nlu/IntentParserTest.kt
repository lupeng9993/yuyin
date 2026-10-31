package com.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议解析测试：覆盖多动作、围栏清洗、越界截断、字段缺失降级、旧格式兼容。
 * 用 org.json（testImplementation 里的实现）替代 Android 自带实现。
 */
class IntentParserTest {

    private fun parse(json: String) = IntentParser.parse(json, ActionPlan.Source.CLOUD)

    @Test
    fun `多动作解析`() {
        val json = """
            {"actions":[
              {"action":"navigate","destination":"大明湖景区"},
              {"action":"send_wechat","contact":"张三","message":"马上下楼"}
            ]}
        """.trimIndent()
        val outcome = parse(json)
        assertTrue(outcome is ParseOutcome.Success)
        val plan = (outcome as ParseOutcome.Success).plan
        assertEquals(2, plan.actions.size)
        assertEquals(ActionType.NAVIGATE, plan.actions[0].type)
        assertEquals(ActionType.SEND_WECHAT, plan.actions[1].type)
        assertEquals(ActionPlan.Source.CLOUD, plan.source)
        assertFalse(plan.isUnknown)
    }

    @Test
    fun `代码围栏与前后说明被清掉`() {
        val raw = """
            好的，下面是解析结果：
            ```json
            {"actions":[{"action":"navigate","destination":"泉城广场"}]}
            ```
            希望对你有帮助。
        """.trimIndent()
        val outcome = parse(raw)
        assertTrue(outcome is ParseOutcome.Success)
        assertEquals("泉城广场", (outcome as ParseOutcome.Success).plan.actions.single().destination)
    }

    @Test
    fun `超过五个动作只保留前五个`() {
        val actions = (1..8).joinToString(",") {
            """{"action":"other","raw_text":"打开应用$it"}"""
        }
        val outcome = parse("""{"actions":[$actions]}""")
        assertTrue(outcome is ParseOutcome.Success)
        assertEquals(ActionPlan.MAX_ACTIONS, (outcome as ParseOutcome.Success).plan.actions.size)
    }

    @Test
    fun `混入 unknown 时整组判定为 unknown`() {
        val json = """
            {"actions":[
              {"action":"other","raw_text":"打开网易云"},
              {"action":"unknown","reason":"看不懂"}
            ]}
        """.trimIndent()
        val outcome = parse(json)
        assertTrue(outcome is ParseOutcome.Success)
        assertTrue((outcome as ParseOutcome.Success).plan.isUnknown)
    }

    @Test
    fun `缺字段降级成 unknown`() {
        val outcome = parse("""{"actions":[{"action":"navigate"}]}""")
        assertTrue(outcome is ParseOutcome.Success)
        val action = (outcome as ParseOutcome.Success).plan.actions.single()
        assertEquals(ActionType.UNKNOWN, action.type)
    }

    @Test
    fun `越界动作类型降级成 unknown`() {
        val outcome = parse("""{"actions":[{"action":"bluetooth_connect"}]}""")
        assertTrue(outcome is ParseOutcome.Success)
        assertEquals(ActionType.UNKNOWN, (outcome as ParseOutcome.Success).plan.actions.single().type)
    }

    @Test
    fun `兼容旧单动作格式`() {
        val outcome = parse("""{"action":"send_wechat","contact":"李四","message":"到了"}""")
        assertTrue(outcome is ParseOutcome.Success)
        val plan = (outcome as ParseOutcome.Success).plan
        assertEquals(1, plan.actions.size)
        assertEquals("李四", plan.actions.single().contact)
    }

    @Test
    fun `非 JSON 返回失败`() {
        val outcome = parse("抱歉，我无法理解这句话。")
        assertTrue(outcome is ParseOutcome.Failure)
    }

    @Test
    fun `空动作列表失败`() {
        val outcome = parse("""{"actions":[]}""")
        assertTrue(outcome is ParseOutcome.Failure)
    }

    @Test
    fun `JSON 清洗器只取最外层对象`() {
        val cleaned = JsonCleaner.clean("前缀 {\"a\":1} 后缀")
        assertNotNull(cleaned)
        assertEquals("{\"a\":1}", cleaned)
        assertNull(JsonCleaner.clean("没有大括号"))
    }
}
