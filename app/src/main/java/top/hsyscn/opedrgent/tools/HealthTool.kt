package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import top.hsyscn.opedrgent.health.HealthConnectHelper
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Health Connect 运动健康工具。
 *
 * LLM 可调用此工具读取用户的步数、心率、睡眠、卡路里等运动健康数据。
 * 需要用户在设置中开启"运动健康"并授权 Health Connect 权限。
 */
class HealthTool(private val context: Context) : ToolSet {

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "health_read" to ToolBinding(
                name = "health_read",
                description = "读取用户的运动健康数据（步数、心率、睡眠、卡路里等）。支持查询今日摘要、最近步数、最近睡眠。",
                parameters = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query_type", JSONObject().apply {
                            put("type", "string")
                            put("description", "查询类型: summary(今日摘要), steps(最近步数), sleep(最近睡眠)")
                            put("enum", org.json.JSONArray().apply {
                                put("summary")
                                put("steps")
                                put("sleep")
                            })
                        })
                        put("days", JSONObject().apply {
                            put("type", "integer")
                            put("description", "查询天数（仅 steps 模式有效，默认7天）")
                        })
                    })
                    put("required", org.json.JSONArray().apply { put("query_type") })
                },
                invoker = ::executeHealthRead,
            )
        )
    }

    private suspend fun executeHealthRead(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        cancelled: Boolean,
    ): ToolResult {
        val queryType = tp.state.input["query_type"] ?: "summary"

        return try {
            val availability = HealthConnectHelper.getAvailability(context)
            if (availability != top.hsyscn.opedrgent.health.HealthConnectAvailability.Available) {
                return errorResult(tp, "Health Connect 不可用，请先在系统设置中安装并授权 Health Connect 应用")
            }

            val result = when (queryType) {
                "summary" -> {
                    HealthConnectHelper.getTodaySummary(context)
                        ?: "今日暂无运动数据"
                }
                "steps" -> {
                    val days = tp.state.input["days"]?.toIntOrNull() ?: 7
                    HealthConnectHelper.getRecentSteps(context, days)
                        ?: "暂无步数数据，请检查 Health Connect 权限"
                }
                "sleep" -> {
                    HealthConnectHelper.getRecentSleep(context)
                        ?: "暂无睡眠数据"
                }
                else -> "未知查询类型: $queryType，支持 summary/steps/sleep"
            }

            DebugLog.d("HealthTool: query=$queryType, result=${result.take(100)}")
            successResult(tp, result)
        } catch (e: SecurityException) {
            DebugLog.e("HealthTool: permission denied: ${e.message}")
            errorResult(tp, "Health Connect 权限未授予，请在设置中开启运动健康并授权")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("HealthTool: error: ${e.message}")
            errorResult(tp, "读取健康数据失败: ${e.message}")
        }
    }

    private fun successResult(tp: ToolPart, output: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = output,
                endTime = System.currentTimeMillis(),
            ))
        )
    }

    private fun errorResult(tp: ToolPart, message: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = message,
                endTime = System.currentTimeMillis(),
            ))
        )
    }
}
