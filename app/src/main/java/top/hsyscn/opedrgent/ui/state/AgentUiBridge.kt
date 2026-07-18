package top.hsyscn.opedrgent.ui.state

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.R
import top.hsyscn.opedrgent.ui.components.ConfirmationOption
import top.hsyscn.opedrgent.ui.components.ConfirmationRequest
import top.hsyscn.opedrgent.ui.components.QuestionInfo
import top.hsyscn.opedrgent.ui.components.QuestionOption
import top.hsyscn.opedrgent.ui.components.QuestionRequest

/**
 * AgentService 与 UI 层之间的桥接辅助类。
 *
 * 负责将 Agent 返回的结构化输入解析为 UI 可用的 Question/Confirmation 请求，
 * 以及将用户响应序列化为 Agent 可消费的 JSON 结果。
 */
class AgentUiBridge(private val app: Application) {

    fun parseQuestionInput(input: Map<String, String>): QuestionRequest {
        val questionsJson = input["questions"] ?: "[]"
        val arr = JSONArray(questionsJson)
        val questions = (0 until arr.length()).map { i ->
            val q = arr.getJSONObject(i)
            val optsArr = q.getJSONArray("options")
            val options = (0 until optsArr.length()).map { j ->
                val opt = optsArr.getJSONObject(j)
                QuestionOption(
                    label = opt.getString("label"),
                    description = opt.optString("description", ""),
                )
            }
            QuestionInfo(
                question = q.getString("question"),
                header = q.optString("header", ""),
                options = options,
                multiple = q.optBoolean("multiple", false),
                allowCustom = q.optBoolean("allowCustom", false),
            )
        }
        return QuestionRequest(questions = questions)
    }

    fun parseConfirmationInput(input: Map<String, String>): ConfirmationRequest {
        val optionsStr = input["options"] ?: app.getString(R.string.confirm_options_default)
        val options = optionsStr.split(",").map { ConfirmationOption(label = it.trim()) }
        return ConfirmationRequest(
            message = input["message"] ?: app.getString(R.string.msg_confirm_execute_default),
            detail = input["detail"] ?: "",
            options = options,
            timeoutSeconds = input["timeoutSeconds"]?.toIntOrNull() ?: 30,
        )
    }

    fun buildQuestionResultJson(answers: List<List<String>>): String {
        val result = answers.mapIndexed { idx, ans ->
            mapOf("question" to "Q${idx + 1}", "answers" to ans)
        }
        return JSONObject(mapOf("answers" to result)).toString()
    }

    fun buildConfirmationResultJson(selectedOption: String?, request: ConfirmationRequest?): String {
        if (selectedOption == "__confirmed__") {
            return JSONObject(mapOf("confirmed" to true, "timeout" to false)).toString()
        }
        return JSONObject(mapOf(
            "confirmed" to (selectedOption != null),
            "selectedOption" to (selectedOption ?: ""),
            "timeout" to false,
        )).toString()
    }
}
