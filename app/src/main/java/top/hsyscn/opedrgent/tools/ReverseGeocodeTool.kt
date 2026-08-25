package top.hsyscn.opedrgent.tools

import android.content.Context
import top.hsyscn.opedrgent.R

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog
import java.util.Locale

/**
 * 地理编码工具：正向（地名→经纬度）+ 反向（经纬度→地址）
 *
 * 正向地理编码使用场景：用户提到远程地点（如"哈工大"/"马里兰大学"）需要查询卫星过境时，
 * 先调用本工具获取经纬度，再传给 satellite_pass 工具的 lat/lon 参数。
 */
class ReverseGeocodeTool(
    private val context: Context,
    private val searcher: WebSearcher,
) : ToolSet {

    @Tool("geocode")
    @ToolDescription("地理编码工具，支持正向（地名→经纬度）和反向（经纬度→地址）。action=forward 时 place 为必填；action=reverse 时 lat/lon 为必填。")
    fun executeGeocode(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val action = tp.state.input["action"]?.trim()?.lowercase() ?: "forward"
        return when (action) {
            "forward" -> executeForwardGeocode(tp)
            "reverse" -> executeReverseGeocode(tp)
            else -> ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = context.getString(R.string.error_invalid_action, action),
                endTime = System.currentTimeMillis(),
            )))
        }
    }

    private fun executeForwardGeocode(tp: ToolPart): ToolResult {
        val place = tp.state.input["place"]?.trim()
        if (place.isNullOrBlank()) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.ERROR,
                error = context.getString(R.string.error_forward_needs_place),
                endTime = System.currentTimeMillis(),
            )))
        }

        DebugLog.i("geocode forward: $place")
        val result = searcher.forwardGeocode(place)
        return if (result != null) {
            val (lat, lon) = result
            ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = "地名: $place → 经纬度: ${lat.format(6)}°, ${lon.format(6)}°",
                endTime = System.currentTimeMillis(),
            )))
        } else {
            ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = context.getString(R.string.error_geocode_not_found, place),
                endTime = System.currentTimeMillis(),
            )))
        }
    }

    private fun executeReverseGeocode(tp: ToolPart): ToolResult {
        val lat = tp.state.input["lat"]?.toDoubleOrNull()
        val lon = tp.state.input["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = context.getString(R.string.error_missing_lat_lon), endTime = System.currentTimeMillis())))
        }

        DebugLog.i("geocode reverse: $lat, $lon")
        val result = searcher.reverseGeocode(lat, lon)
        if (result != null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = result,
                endTime = System.currentTimeMillis(),
            )))
        }

        val envGeo = top.hsyscn.opedrgent.env.EnvironmentProvider.reverseGeocode(lat, lon)
        if (envGeo != null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(
                status = ToolStateType.COMPLETED,
                output = envGeo.displayName,
                endTime = System.currentTimeMillis(),
            )))
        }

        return ToolResult(toolPart = tp.copy(state = tp.state.copy(
            status = ToolStateType.COMPLETED,
            output = context.getString(R.string.error_reverse_geocode_failed, String.format(Locale.US, "%.6f", lat), String.format(Locale.US, "%.6f", lon)),
            endTime = System.currentTimeMillis(),
        )))
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "geocode" to ToolBinding(
                name = "geocode",
                description = """地理编码工具（正向 + 反向）。

支持两个 action：

1) action=forward - 正向地理编码：地名/地址 → 经纬度
   参数: place(必填, 地名或地址，如"哈尔滨工业大学"/"马里兰大学"/"北京市海淀区")
   使用场景: 用户提到远程地点需要查卫星过境时，先调用此 action 获取 lat/lon，再传给 satellite_pass 工具

2) action=reverse - 反向地理编码：经纬度 → 地址
   参数: lat(必填, 纬度), lon(必填, 经度)
   使用场景: 用户提供经纬度需要转换成具体地址时

*** 卫星过境链路（强制）：用户提到某个"非本地地点"的卫星过境时，必须先 action=forward 获取坐标，再调用 satellite_pass(lat=..., lon=...) ***
典型流程: action=forward(place="哈工大") → 拿到 lat/lon → action=passes(satellite="ISS", lat=45.773, lon=126.693)""",
                invoker = { tp, config, sp, ups -> executeGeocode(tp, config, sp, ups) },
            ),
            // 保留 reverse_geocode 作为别名，向后兼容
            "reverse_geocode" to ToolBinding(
                name = "reverse_geocode",
                description = "将经纬度坐标逆地理编码为地址（geocode action=reverse 的别名）。参数中 lat(纬度) 和 lon(经度) 为必填。",
                invoker = { tp, config, sp, ups -> executeGeocode(tp.withAction("reverse"), config, sp, ups) },
            ),
        )
    }

    private fun Double.format(decimals: Int): String = String.format("%.${decimals}f", this)

    /** 为 ToolPart 添加 action 参数（用于别名转发） */
    private fun ToolPart.withAction(action: String): ToolPart =
        copy(state = state.copy(input = state.input + ("action" to action)))
}
