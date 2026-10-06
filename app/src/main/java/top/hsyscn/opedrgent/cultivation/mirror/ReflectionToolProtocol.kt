package top.hsyscn.opedrgent.cultivation.mirror

import org.json.JSONObject

/** 模型声明的一次本地工具调用。[args] 为工具参数对象，缺省为空对象。 */
data class ReflectionToolCall(
    val name: String,
    val args: JSONObject,
)

/**
 * 端侧最小工具意图协议。
 *
 * 立场是“模型为大”：是否取长期记忆、是否更新对用户的认识，由模型在会话中自主声明，工程不替它决定。
 * 考虑到端侧约 1B 级小模型的 function-calling 并不可靠，协议刻意做到极简、可跳过、有兜底。
 *
 * 工具分两类、出现在不同阶段：
 * - 只读工具（[RECENT]/[MEMORY]）：模型可在最终报告前请求一轮，工程回填后模型必须直接出报告；
 * - 画像写入工具（[PERSONA_UPDATE]）：模型在输出最终报告的同一 JSON 里附带，用来自动更新“对用户的认识”，
 *   工程静默落库、不再发起新一轮模型调用（零额外往返）。
 *
 * 工程只做结构化解析与参数夹紧，不根据内容做任何语义判定。
 */
object ReflectionToolProtocol {

    /** 取回最近几次自我复盘的总体结论，用于识别跨时间模式。 */
    const val RECENT = "recall_recent"

    /** 按关键词从以往录音 / 对话 / 笔记等长期记忆中检索相关背景。 */
    const val MEMORY = "recall_memory"

    /** 自动人格画像更新：随最终报告同批返回，模型据此持久化“对用户的认识”。 */
    const val PERSONA_UPDATE = "persona_update"

    /** 首轮允许的只读工具。 */
    private val READ_TOOLS = setOf(RECENT, MEMORY)

    /**
     * 解析“首轮只读工具请求”：仅识别 [RECENT]/[MEMORY]；画像写入或最终报告都返回 null。
     */
    fun parseToolCalls(raw: String): List<ReflectionToolCall>? {
        val text = MirrorPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("toolCalls") ?: return null
        val calls = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name", "").trim()
            if (name !in READ_TOOLS) return@mapNotNull null
            ReflectionToolCall(name, o.optJSONObject("args") ?: JSONObject())
        }
        return calls.takeIf { it.isNotEmpty() }
    }

    /**
     * 从最终报告 JSON 中提取画像更新（[PERSONA_UPDATE]）；没有则返回 null。
     * 画像更新与报告同批、不占用额外的模型往返。
     */
    fun parsePersonaUpdate(raw: String): ReflectionToolCall? {
        val text = MirrorPromptBuilder.extractJsonObject(raw)
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("toolCalls") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name == PERSONA_UPDATE) {
                return ReflectionToolCall(PERSONA_UPDATE, o.optJSONObject("args") ?: JSONObject())
            }
        }
        return null
    }

    /** 注入系统提示的工具能力说明。 */
    fun toolContract(): String = """
        你在持续观察中自动认识用户，用户不填写任何“人格基准”。
        一、需要长期记忆时（全程在设备本地、不上传），可在最终报告前先只输出一个对象请求一轮：
        {"toolCalls":[{"name":"recall_recent","args":{"limit":5}}]}
        - recall_recent：取回最近几次自我复盘的总体结论，args.limit 取 1-8，用于识别跨时间反复出现的模式。
        - recall_memory：按 args.keyword 从你以往的录音、对话、笔记等长期记忆里检索相关背景，args.limit 取 1-5；不给 keyword 时按本次转写自动取词。
        二、输出最终报告时，可在报告 JSON 里同时附带对画像的更新（把你“对用户的认识”持久化）：
        {"toolCalls":[{"name":"persona_update","args":{"actual_self":[{"text":"具体行为","evidence":"逐字原话"}],"aspired_self":[{"text":"价值方向","evidence":"逐字原话"}],"open_questions":["待观察点"]}}]}
        - actual_self：现实自我，观察到、且有逐字证据的具体行为（对事不对人、不贴人格标签）；
        - aspired_self：理想自我，从用户自己的表达（自我期许、推崇的人或原则、自我批评流露的目标）中“推断”的方向，不是用户填写的目标；
        - open_questions：你尚不确定、后续继续观察的备忘，不要拿去追问用户。
        每条 text 必须配 evidence（逐字原话、真实出现）；证据不足就不写或放进 open_questions，绝不编造、不做关键词命中。
        不需要长期记忆就直接输出最终报告 JSON，不要为走流程而请求工具；只读工具最多一轮，拿到结果后下一次必须直接输出最终报告。
    """.trimIndent()

    /** 把模型给的条数夹紧到合法区间；给了非正数时用默认值。 */
    fun clampLimit(raw: Int, low: Int, high: Int, default: Int): Int =
        if (raw <= 0) default else raw.coerceIn(low, high)
}
