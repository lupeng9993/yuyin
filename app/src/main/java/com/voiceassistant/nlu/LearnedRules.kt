package com.voiceassistant.nlu

import android.content.Context
import com.voiceassistant.log.DebugLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自动学习：本地规则没命中、云端解析成功执行过之后，把这句话记成**模板**，
 * 下次同样句式就不再走云端。
 *
 * 例：
 *   原句「给老王发微信说晚上六点见」→ 模板「给{contact}发微信说{message}」
 *   下次「给小李发微信说明天见」→ 直接命中，不花钱也不联网。
 *
 * 安全约束：
 * - 只学执行成功的（调用方保证）
 * - 模板至少 6 个字、占位符至少 1 个字，避免误匹配
 * - 最多 200 条，超了淘汰命中最少的
 * - 只存本机，不上传
 */
object LearnedRules {

    private const val TAG = "Learn"
    private const val PREFS = "va_learned_rules"
    private const val KEY = "rules_json"
    private const val MAX_RULES = 200
    private const val MIN_TEMPLATE_LEN = 6

    /** 可以对上号的字段名（其余字段不参与模板化） */
    private val FIELD_NAMES = listOf("contact", "message", "destination")

    data class Rule(
        val template: String,
        val planJson: String,
        val hits: Int,
        val createdAt: Long
    )

    // ---------------- 学习 ----------------

    /** 学习一条；返回是否真的学到了 */
    fun learn(context: Context, utterance: String, plan: ActionPlan): Boolean {
        val text = utterance.trim()
        if (text.length < MIN_TEMPLATE_LEN) return false
        if (plan.isUnknown) return false

        val template = buildTemplate(text, plan) ?: return false
        if (template.length < MIN_TEMPLATE_LEN) return false

        val rules = load(context)
        // 同一模板只留一条，顺便把命中数 +1
        val existing = rules.indexOfFirst { it.template == template }
        if (existing >= 0) {
            rules[existing] = rules[existing].copy(hits = rules[existing].hits + 1)
        } else {
            rules.add(
                Rule(
                    template = template,
                    planJson = planToJson(plan),
                    hits = 1,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        save(context, rules)
        DebugLog.d(TAG, "已学习模板：$template")
        return true
    }

    /** 用云端返回的字段值在原句里的位置，把可变部分换成占位符 */
    private fun buildTemplate(text: String, plan: ActionPlan): String? {
        var template = text
        var replaced = false
        for (action in plan.actions) {
            for (field in FIELD_NAMES) {
                val value = fieldValue(action, field)
                if (value.isBlank() || value.length < 1) continue
                if (!template.contains(value)) continue
                template = template.replaceFirst(value, "{$field}")
                replaced = true
            }
        }
        return if (replaced) template else null
    }

    // ---------------- 匹配 ----------------

    /** 看看有没有学过的模板能套上；套上就返回动作计划 */
    fun match(context: Context, utterance: String): ActionPlan? {
        val text = utterance.trim()
        if (text.isEmpty()) return null
        val rules = load(context)
        var hitIndex = -1
        var captures: Map<String, String> = emptyMap()

        for ((index, rule) in rules.withIndex()) {
            val regex = templateToRegex(rule.template) ?: continue
            val m = regex.matchEntire(text) ?: continue
            val found = HashMap<String, String>()
            val names = placeholders(rule.template)
            names.forEachIndexed { i, name ->
                val value = m.groupValues.getOrNull(i + 1).orEmpty()
                if (value.isNotEmpty()) found[name] = value
            }
            if (found.isEmpty()) continue
            hitIndex = index
            captures = found
            break
        }

        if (hitIndex < 0) return null
        val rule = rules[hitIndex]
        val plan = planFromJson(rule.planJson) ?: return null

        // 命中数 +1，用于淘汰最不常用的
        rules[hitIndex] = rule.copy(hits = rule.hits + 1)
        save(context, rules)

        val filled = applyCaptures(plan, captures)
        DebugLog.d(TAG, "命中已学模板：${rule.template} 参数=$captures")
        return filled
    }

    private fun applyCaptures(plan: ActionPlan, captures: Map<String, String>): ActionPlan {
        val actions = plan.actions.map { a ->
            var na = a
            captures["contact"]?.let { na = na.copy(contact = it) }
            captures["message"]?.let { na = na.copy(message = it) }
            captures["destination"]?.let { na = na.copy(destination = it) }
            na
        }
        return plan.copy(actions = actions)
    }

    /** 「给{contact}发微信说{message}」→ 正则 */
    private fun templateToRegex(template: String): Regex? {
        val sb = StringBuilder("^")
        var i = 0
        while (i < template.length) {
            if (template[i] == '{') {
                val end = template.indexOf('}', i)
                if (end < 0) return null
                sb.append("(.+?)")
                i = end + 1
            } else {
                sb.append(Regex.escape(template[i].toString()))
                i++
            }
        }
        sb.append("$")
        return try {
            Regex(sb.toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun placeholders(template: String): List<String> =
        Regex("\\{([a-zA-Z]+)}").findAll(template).map { it.groupValues[1] }.toList()

    private fun fieldValue(action: VaAction, field: String): String = when (field) {
        "contact" -> action.contact
        "message" -> action.message
        "destination" -> action.destination
        else -> ""
    }

    // ---------------- 存储 ----------------

    fun load(context: Context): MutableList<Rule> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, "") ?: return mutableListOf()
        if (raw.isBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Rule(
                    template = o.optString("t"),
                    planJson = o.optString("p"),
                    hits = o.optInt("h", 1),
                    createdAt = o.optLong("c", 0L)
                )
            }.toMutableList()
        } catch (e: Exception) {
            DebugLog.e(TAG, "读取已学规则失败", e)
            mutableListOf()
        }
    }

    fun save(context: Context, rules: List<Rule>) {
        val trimmed = if (rules.size > MAX_RULES) {
            rules.sortedByDescending { it.hits }.take(MAX_RULES)
        } else {
            rules
        }
        val arr = JSONArray()
        trimmed.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("t", r.template)
                    put("p", r.planJson)
                    put("h", r.hits)
                    put("c", r.createdAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        DebugLog.d(TAG, "已清空全部学习记录")
    }

    fun count(context: Context): Int = load(context).size

    /** 设置页展示用：模板 + 命中次数 */
    fun summary(context: Context): List<String> =
        load(context).sortedByDescending { it.hits }.map { "${it.template}（命中 ${it.hits}）" }

    // ---------------- 计划序列化 ----------------

    private fun planToJson(plan: ActionPlan): String {
        val arr = JSONArray()
        plan.actions.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("action", a.type.wire)
                    put("raw_text", a.rawText)
                    put("contact", a.contact)
                    put("message", a.message)
                    put("destination", a.destination)
                }
            )
        }
        return arr.toString()
    }

    private fun planFromJson(json: String): ActionPlan? = try {
        val arr = JSONArray(json)
        val actions = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VaAction(
                type = ActionType.fromWire(o.optString("action")) ?: return@mapNotNull null,
                destination = o.optString("destination"),
                contact = o.optString("contact"),
                message = o.optString("message"),
                rawText = o.optString("raw_text")
            )
        }
        if (actions.isEmpty()) null else ActionPlan(actions, ActionPlan.Source.LOCAL_RULE)
    } catch (e: Exception) {
        null
    }
}
