package top.hsyscn.opedrgent.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.cultivation.model.PersonaProfile
import top.hsyscn.opedrgent.cultivation.model.PersonaTrait
import top.hsyscn.opedrgent.cultivation.model.PersonaTraitStatus
import top.hsyscn.opedrgent.cultivation.store.PersonaProfileStore
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.emptyResult

/**
 * 人格画像工具 —— 让模型把“对用户的认识”持久化到本地、并持续自动演进。
 *
 * ## 模型驱动、零配置
 * 用户不需要填写任何“理想人格基准”。模型在持续观察用户本人的言行（录音转写、对话）后，
 * 经本工具自动维护一份两层画像：现实自我（观察到的行为）与理想自我（从用户表达中推断的方向）。
 * 本类只提供工具名、描述与 JSON Schema，是否调用、写入什么由模型决定，不做任何关键词命中或意图判定。
 *
 * ## 两个工具
 * - [TOOL_GET]：读取当前画像（通常会随上下文自动注入，需要中途核对时调用）；
 * - [TOOL_UPDATE]：提交更新后的完整画像（保留仍准确的条目、新增带证据的观察）。
 */
class PersonaTool(private val context: Context) : ToolSet {

    private val store: PersonaProfileStore by lazy { PersonaProfileStore(context) }

    override fun getTools(): Map<String, ToolBinding> = mapOf(
        TOOL_GET to ToolBinding(
            name = TOOL_GET,
            description = """读取你为用户自动建立的人格画像（现实自我 actual_self 与理想自我 aspired_self）。
画像由你在持续观察用户本人的言行后自动维护，用户不填写；每条都带逐字证据。画像通常会随上下文自动给你，
当你怀疑持有的画像不是最新、或需要中途重新核对时调用。""",
            parameters = JSONObject("""{"type":"object","properties":{}}"""),
            invoker = { tp, _, _, _ -> get(tp) },
        ),
        TOOL_UPDATE to ToolBinding(
            name = TOOL_UPDATE,
            description = """更新你为用户自动建立的人格画像（这是你“认识用户”的持久化，不是向用户提问）。
你会拿到当前画像；请结合本次言行返回更新后的“完整”画像：保留仍准确的条目（不再成立的标 retired、
被修正的标 superseded），新增有逐字证据支撑的观察。
- actual_self：现实自我，观察到、且有证据支撑的具体行为模式（对事不对人、不贴人格标签）；
- aspired_self：理想自我，从用户自己的表达（自我期许、推崇的人或原则、自我批评流露的目标）中“推断”的方向，
  不是用户填写的目标；
- open_questions：你尚不确定、计划在后续继续观察的点（给你自己的备忘，不要拿去追问用户）。
铁律：每条 text 必须配 evidence（逐字原话、真实出现）；证据不足就不要写或放进 open_questions，绝不编造，
也不要做关键词命中。""",
            parameters = JSONObject("""
                {
                    "type": "object",
                    "properties": {
                        "actual_self": {
                            "type": "array",
                            "description": "更新后的现实自我完整列表",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "text": { "type": "string", "description": "一条具体、可观察的行为，对事不对人" },
                                    "evidence": { "type": "string", "description": "支撑该条的逐字原话，必须真实出现" },
                                    "source": { "type": "string", "description": "可选，来源标签" },
                                    "status": { "type": "string", "enum": ["active", "superseded", "retired"], "description": "条目状态，默认 active" }
                                },
                                "required": ["text"]
                            }
                        },
                        "aspired_self": {
                            "type": "array",
                            "description": "更新后的理想自我完整列表",
                            "items": {
                                "type": "object",
                                "properties": {
                                    "text": { "type": "string", "description": "一条具体的价值取向或方向" },
                                    "evidence": { "type": "string", "description": "支撑该推断的逐字原话，必须真实出现" },
                                    "source": { "type": "string", "description": "可选，来源标签" },
                                    "status": { "type": "string", "enum": ["active", "superseded", "retired"], "description": "条目状态，默认 active" }
                                },
                                "required": ["text"]
                            }
                        },
                        "open_questions": {
                            "type": "array",
                            "description": "尚待观察的备忘，字符串数组",
                            "items": { "type": "string" }
                        }
                    }
                }
            """),
            invoker = { tp, _, _, _ -> update(tp) },
        ),
    )

    /** 读取画像。 */
    private suspend fun get(tp: ToolPart): ToolResult {
        val profile = store.get()
        val text = if (profile == null) {
            "画像尚未建立：这通常是刚开始使用、证据还不足。请在后续分析中用 persona_update 逐步建立。"
        } else {
            buildString {
                appendLine("当前画像（版本 ${profile.version}）：")
                append(PersonaProfileStore.encode(profile))
            }
        }
        return completed(tp, text.trim())
    }

    /** 保存模型给出的完整新画像。 */
    private suspend fun update(tp: ToolPart): ToolResult {
        val input = tp.state.input
        val actual = parseTraits(input["actual_self"])
        val aspired = parseTraits(input["aspired_self"])
        val openQuestions = parseStringList(input["open_questions"])

        if (actual.isEmpty() && aspired.isEmpty() && openQuestions.isEmpty()) {
            return emptyResult(tp, "未提供任何画像内容（actual_self/aspired_self/open_questions 均为空），未写入")
        }

        val now = System.currentTimeMillis()
        val profile = PersonaProfile(
            actualSelf = actual.ifEmpty { emptyList() },
            aspiredSelf = aspired,
            openQuestions = openQuestions,
            updatedAt = now,
        )
        val saved = store.save(profile)
        fun activeCount(list: List<PersonaTrait>) = list.count { it.status == PersonaTraitStatus.ACTIVE }
        val text = buildString {
            appendLine("画像已更新到版本 ${saved.version}。")
            appendLine("- 现实自我：现行 ${activeCount(saved.actualSelf)} 条（共 ${saved.actualSelf.size} 条）")
            appendLine("- 理想自我：现行 ${activeCount(saved.aspiredSelf)} 条（共 ${saved.aspiredSelf.size} 条）")
            if (saved.openQuestions.isNotEmpty()) appendLine("- 待观察：${saved.openQuestions.size} 条")
        }
        return completed(tp, text.trim())
    }

    private fun parseTraits(raw: String?): List<PersonaTrait> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text", "").trim()
                if (text.isBlank()) return@mapNotNull null
                PersonaTrait(
                    text = text,
                    evidence = o.optString("evidence", "").trim(),
                    sourceLabel = o.optString("source", "").trim(),
                    observedAt = o.optLong("observedAt", System.currentTimeMillis()),
                    status = PersonaTraitStatus.fromName(o.optString("status", "active")),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun parseStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.map { s -> s.trim() }.filter { it.isNotBlank() }
        }.getOrElse { emptyList() }
    }

    private fun completed(tp: ToolPart, text: String): ToolResult = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = text,
                endTime = System.currentTimeMillis(),
            ),
        ),
    )

    companion object {
        private const val TOOL_GET = "persona_get"
        private const val TOOL_UPDATE = "persona_update"
    }
}
