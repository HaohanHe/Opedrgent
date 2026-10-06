package top.hsyscn.opedrgent.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig

/**
 * [LocalOpenApiToolAdapter] 在 Robolectric 下的纯逻辑验证：
 *  - 工具描述 JSON（名称 / 描述 / JSON Schema）；
 *  - native 入参 JSON → ToolBinding 约定 state.input 的映射（字符串原样、复合结构紧凑序列化）；
 *  - 工具输出 / 错误回灌为合法 JSON。
 *
 * 不触碰 native 推理与真实工具，仅验证适配层；native 自动工具回调的多轮行为需真机确认。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalOpenApiToolAdapterTest {

    private val apiConfig = ApiConfig(baseUrl = "", apiKey = "", model = "")

    private fun adapter(
        block: suspend (ToolPart) -> ToolResult,
    ): LocalOpenApiToolAdapter {
        val binding = ToolBinding(
            name = "echo",
            description = "echo tool",
            parameters = JSONObject(
                """{"type":"object","properties":{"id":{"type":"string"}}}""",
            ),
            invoker = { tp, _, _, _ -> block(tp) },
        )
        return LocalOpenApiToolAdapter(binding, apiConfig)
    }

    private fun ok(tp: ToolPart, output: String) = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(status = ToolStateType.COMPLETED, output = output),
        ),
    )

    private fun fail(tp: ToolPart, error: String) = ToolResult(
        toolPart = tp.copy(
            state = tp.state.copy(status = ToolStateType.ERROR, error = error),
        ),
    )

    @Test
    fun descriptionContainsNameSchemaAndDescription() {
        val a = adapter { ok(it, "x") }
        val desc = JSONObject(a.getToolDescriptionJsonString())
        assertEquals("echo", desc.getString("name"))
        assertEquals("echo tool", desc.getString("description"))
        assertEquals("object", desc.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun stringParamPassedThroughAndOutputEnvelopedAsResult() {
        val a = adapter { tp ->
            assertEquals("abc", tp.state.input["id"])
            ok(tp, "done-output")
        }
        val raw = a.execute("""{"id":"abc"}""")
        // 成功统一为对象信封 {"result":...}，不再是被引号包裹的裸字符串
        val obj = JSONObject(raw)
        assertEquals("done-output", obj.getString("result"))
    }

    @Test
    fun complexParamSerializedCompactIntoInput() {
        val a = adapter { tp ->
            val todos = tp.state.input["todos"]
            assertTrue(todos != null && todos.contains("content"))
            ok(tp, "saved")
        }
        a.execute("""{"todos":[{"content":"a"}]}""")
    }

    @Test
    fun blankParamsYieldEmptyInput() {
        val a = adapter { tp ->
            assertTrue(tp.state.input.isEmpty())
            ok(tp, "ok")
        }
        a.execute("")
    }

    @Test
    fun toolErrorReturnedAsErrorJson() {
        val a = adapter { fail(it, "boom") }
        val raw = a.execute("""{"id":"x"}""")
        assertEquals("boom", JSONObject(raw).getString("error"))
    }
}
