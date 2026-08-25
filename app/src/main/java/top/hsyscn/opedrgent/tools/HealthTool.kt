package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import top.hsyscn.opedrgent.R
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

    companion object {
        private const val MAX_DAYS = 30
        private const val DEFAULT_DAYS = 7
    }

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
                            put("description", "查询天数（仅 steps 模式有效，默认7天，最大30天）")
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
        useProviderSearch: Boolean,
    ): ToolResult {
        val queryType = tp.state.input["query_type"] ?: "summary"

        return try {
            val availability = HealthConnectHelper.getAvailability(context)
            if (availability != top.hsyscn.opedrgent.health.HealthConnectAvailability.Available) {
                return errorResult(tp, context.getString(R.string.health_unavailable))
            }

            // 检查权限是否仍然有效（用户可能在系统设置中撤销了权限）
            if (!HealthConnectHelper.hasAllPermissions(context)) {
                return errorResult(tp, context.getString(R.string.health_permission_revoked))
            }

            val result = when (queryType) {
                "summary" -> {
                    HealthConnectHelper.getTodaySummary(context)
                        ?: context.getString(R.string.health_no_today_data)
                }
                "steps" -> {
                    val rawDays = tp.state.input["days"]?.toIntOrNull() ?: DEFAULT_DAYS
                    val days = rawDays.coerceIn(1, MAX_DAYS)
                    HealthConnectHelper.getRecentSteps(context, days)
                        ?: context.getString(R.string.health_no_steps_data)
                }
                "sleep" -> {
                    HealthConnectHelper.getRecentSleep(context)
                        ?: context.getString(R.string.health_no_sleep_data)
                }
                else -> context.getString(R.string.health_unknown_query, queryType)
            }

            DebugLog.d("HealthTool: query=$queryType, result=${result.take(100)}")
            successResult(tp, result)
        } catch (e: SecurityException) {
            DebugLog.e("HealthTool: permission denied: ${e.message}")
            errorResult(tp, context.getString(R.string.health_permission_denied))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e("HealthTool: error: ${e.message}")
            errorResult(tp, context.getString(R.string.health_read_failed, e.message ?: ""))
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
