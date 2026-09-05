package com.adfxcbnm.hardeningtool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 方法级抽取规则模板与方案管理。
 * 提供内置常用关键词方案（会员/vip/登录/支付等），支持将当前规则保存为自定义方案、
 * 加载或删除方案，全部持久化到 SharedPreferences（JSON）。
 */
object MethodRuleTemplate {

    private const val PREF = "adfxcbnm_settings"
    private const val KEY_PRESETS = "method_rule_presets"

    data class Preset(val name: String, val rules: String, val isBuiltin: Boolean = false)

    /**
     * 内置常用抽取关键词（方法名包含即命中），生成规则时每关键词一行 `.*关键词.*`。
     * 用户可通过方法与类名匹配甄别是否命中业务敏感逻辑后自行增删。
     */
    private val BUILTIN_KEYWORDS = listOf(
        "vip", "data", "info", "time", "login", "premium",
        "auth", "token", "user", "account", "password", "secret",
        "key", "encrypt", "decrypt", "sign", "verify", "pay",
        "check", "config", "session", "profile", "member", "admin"
    )

    /** 内置方案列表（不可删除，可加载）。 */
    val BUILTIN_PRESETS: List<Preset> = listOf(
        builtinPreset(
            "会员/VIP 核心",
            buildKeywordRules(listOf("vip", "premium", "member", "auth", "token"))
        ),
        builtinPreset(
            "敏感业务常用",
            buildKeywordRules(listOf("login", "account", "password", "secret", "key", "encrypt", "decrypt", "verify", "pay"))
        ),
        builtinPreset(
            "数据/信息/时间",
            buildKeywordRules(listOf("data", "info", "time", "config", "session", "profile", "user", "admin"))
        ),
        builtinPreset(
            "全部常用关键词",
            buildKeywordRules(BUILTIN_KEYWORDS)
        ),
        builtinPreset(
            "正则示例（按需修改）",
            """
            regex:Lcom/example/MainActivity;\.onCreate
            regex:Lcom/example/.*Auth.*;\.(login|logout|refresh)
            """.trimIndent()
        )
    )

    private fun builtinPreset(name: String, rules: String): Preset = Preset(name, rules, isBuiltin = true)

    private fun buildKeywordRules(keywords: List<String>): String =
        keywords.joinToString("\n") { ".*$it.*" }

    /**
     * 正则规则语法说明。
     * 内置关键词生成的是 `.*vip.*` 这类「方法名包含关键词」的宽松规则；
     * 若要精确控制，支持以下格式（一行一条）：
     * - `Lcom/foo/Bar;.methodName` 精确类+方法
     * - `Lcom/foo/Bar;.*` 类下全部方法
     * - `Lcom/foo/.*;.onCreate` 类名正则+方法名精确
     * - `regex:<正则>` 完全正则匹配（匹配对象为 类名.方法名）
     */
    val FORMAT_HELP = """
        内置关键词方案已自动生成(如 .*vip.*)，直接"保存"即可使用。
        高级格式(每行一条)：
            Lcom/foo/Bar;.methodName      精确
            Lcom/foo/Bar;.*               类下全部
            Lcom/foo/.*;.onCreate         类名正则
            regex:.+(auth|login).+        完全正则
        留空 = 全量抽取。
    """.trimIndent()

    fun getCustomPresets(context: Context): List<Preset> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_PRESETS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Preset(o.getString("name"), o.getString("rules"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun savePreset(context: Context, name: String, rules: String): Boolean {
        if (name.isBlank() || rules.isBlank()) return false
        val cur = getCustomPresets(context).toMutableList()
        cur.removeAll { it.name == name }
        cur.add(0, Preset(name, rules))
        persist(context, cur)
        return true
    }

    fun deletePreset(context: Context, name: String) {
        val cur = getCustomPresets(context).toMutableList()
        cur.removeAll { it.name == name }
        persist(context, cur)
    }

    private fun persist(context: Context, presets: List<Preset>) {
        val arr = JSONArray()
        presets.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("rules", p.rules))
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESETS, arr.toString()).apply()
    }
}