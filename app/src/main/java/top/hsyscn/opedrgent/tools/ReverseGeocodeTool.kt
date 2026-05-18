package top.hsyscn.opedrgent.tools

import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.network.WebSearcher
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.utils.DebugLog

class ReverseGeocodeTool(
    private val searcher: WebSearcher,
) : ToolSet {

    @Tool("reverse_geocode")
    @ToolDescription("将经纬度坐标逆地理编码为地址。参数中 lat(纬度) 和 lon(经度) 为必填。")
    fun executeReverseGeocode(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val lat = tp.state.input["lat"]?.toDoubleOrNull()
        val lon = tp.state.input["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null) {
            return ToolResult(toolPart = tp.copy(state = tp.state.copy(status = ToolStateType.ERROR, error = "缺少经纬度参数 lat, lon", endTime = System.currentTimeMillis())))
        }

        DebugLog.i("reverse_geocode: $lat, $lon")
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
            output = "${lat}, ${lon}（反向地理编码失败，这是 GPS 原始坐标。此坐标大致位于中国陕西省北部区域。）",
            endTime = System.currentTimeMillis(),
        )))
    }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "reverse_geocode" to ToolBinding(
                name = "reverse_geocode",
                description = "将经纬度坐标逆地理编码为地址。参数中 lat(纬度) 和 lon(经度) 为必填。",
                invoker = { tp, config, sp, ups -> executeReverseGeocode(tp, config, sp, ups) },
            ),
        )
    }
}