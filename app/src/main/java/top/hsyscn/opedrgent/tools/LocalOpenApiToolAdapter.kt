package top.hsyscn.opedrgent.tools

import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolState
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.settings.ApiConfig

/**
 * 把 Opedrgent 自有的 [ToolBinding] 适配为 LiteRT-LM 的 [OpenApiTool]。
 *
 * 这样端侧原生工具调用与云端走同一套「名称 + 描述 + JSON Schema」的工具定义，
 * 由 native 的受约束解码（constrained decoding）决定是否调用，不做任何关键词命中。
 *
 * native 在推理线程同步回调 [execute]：入参为参数 JSON（如 `{"id":"..."}` 或
 * `{"todos":[...]}`），本类把它转成 [ToolBinding] 约定的 `state.input`（Map<String,String>，
 * 复合类型以紧凑 JSON 字符串承载），用 runBlocking 桥接 suspend invoker，
 * 再把工具输出（state.output / state.error）以 JSON 字符串回灌给模型。
 */
class LocalOpenApiToolAdapter(
    private val binding: ToolBinding,
    private val apiConfig: ApiConfig,
) : OpenApiTool {

    override fun getToolDescriptionJsonString(): String {
        val desc = JSONObject()
        desc.put("name", binding.name)
        if (binding.description.isNotBlank()) desc.put("description", binding.description)
        desc.put(
            "parameters",
            binding.parameters ?: JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()),
        )
        return desc.toString()
    }

    override fun execute(paramsJsonString: String): String = runBlocking {
        val input = parseParamsToInput(paramsJsonString)
        val tp = ToolPart(
            tool = binding.name,
            state = ToolState(
                status = ToolStateType.RUNNING,
                input = input,
                startTime = System.currentTimeMillis(),
            ),
        )

        val state = try {
            binding.invoker(tp, apiConfig, "", false).toolPart.state
        } catch (e: Exception) {
            return@runBlocking JSONObject().put("error", e.message ?: "tool failed").toString()
        }

        // 统一对象信封：失败 {"error":...}，成功/空结果 {"result":...}，
        // 不再对成功输出做 JSONObject.quote 裸字符串，避免受约束解码下对象/字符串两种形态混用。
        when {
            !state.error.isNullOrBlank() ->
                JSONObject().put("error", state.error).toString()
            else ->
                JSONObject().put("result", state.output ?: "").toString()
        }
    }

    /** 参数 JSON → ToolBinding 约定的 Map<String,String>；字符串原样，复合结构紧凑序列化。 */
    private fun parseParamsToInput(paramsJsonString: String): Map<String, String> {
        if (paramsJsonString.isBlank()) return emptyMap()
        val obj = try {
            JSONObject(paramsJsonString)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.get(key)
            result[key] = if (value is String) value else value.toString()
        }
        return result
    }
}
