package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONObject

/** 模型声明的一次本地工具调用。[args] 为工具参数对象，缺省为空对象。 */
data class ReflectionToolCall(
    val name: String,
    val args: JSONObject,
)

/**
 * 端侧最小工具意图协议（逻辑重设计 P1）。
 *
 * 立场是“模型为大”：是否取长期记忆、取什么、取多少，由模型在会话中自主声明，工程不替它决定。
 * 考虑到端侧约 1.7B 级小模型的 function-calling 并不可靠，协议刻意做到三件事：
 *  1. 极简：只有一个 toolCalls 数组、两个只读本地工具；
 *  2. 可跳过：模型不输出工具请求时，[parseToolCalls] 返回 null，调用方直接把输出当最终报告；
 *  3. 有兜底：工具识别失败、取数失败都不阻断分析，绝不因工具缺失而降低到“无法分析”。
 *
 * 工程只做结构化解析与参数夹紧，不根据内容做任何语义判定。
 */
object ReflectionToolProtocol {

    /** 取回最近几次自我复盘的总体结论，用于识别跨时间模式。 */
    const val RECENT = "recall_recent"

    /** 按关键词从以往录音 / 对话 / 笔记等长期记忆中检索相关背景。 */
    const val MEMORY = "recall_memory"

    private val KNOWN = setOf(RECENT, MEMORY)

    /**
     * 若 [raw] 是合法工具请求（含至少一个已知工具），返回调用列表；否则返回 null，视为最终报告。
     */
    fun parseToolCalls(raw: String): List<ReflectionToolCall>? {
        val text = MirrorPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("toolCalls") ?: return null
        val calls = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            if (name !in KNOWN) return@mapNotNull null
            ReflectionToolCall(name, o.optJSONObject("args") ?: JSONObject())
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    /** 注入系统提示的工具能力说明：说明可用工具，但明确“不需要就别调、最多调一轮”。 */
    fun toolContract(): String = """
        你可以在给出最终报告前，先请求本机长期记忆（全程在设备本地、不上传）。需要时只输出一个对象：
        {"toolCalls":[{"name":"recall_recent","args":{"limit":5}}]}
        可用的只读工具：
        - recall_recent：取回最近几次自我复盘的总体结论，args.limit 取 1-8，用于识别跨时间反复出现的模式。
        - recall_memory：按 args.keyword 从你以往的录音、对话、笔记等长期记忆里检索相关背景，args.limit 取 1-5；不给 keyword 时按本次转写自动取词。
        不需要长期记忆就直接输出最终报告 JSON，不要为走流程而请求工具。最多请求一轮；一旦拿到工具结果，下一次必须直接输出最终报告 JSON，不得再次请求工具。
    """.trimIndent()

    /** 把模型给的条数夹紧到合法区间；给了非正数时用默认值。 */
    fun clampLimit(raw: Int, low: Int, high: Int, default: Int): Int =
        if (raw <= 0) default else raw.coerceIn(low, high)
}
