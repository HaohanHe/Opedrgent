package top.hsyscn.opedrgent.ui.state

import android.app.Application
import org.json.JSONObject
import top.hsyscn.opedrgent.model.HamContactLog
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.tools.SatellitePassTool
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 业余卫星通联日志辅助类。
 *
 * 负责从文本中识别卫星、合并预填充与 AI 提取结果、
 * 梅登海格网格换算以及 AI 返回 JSON 的解析。
 */
object HamContactLogHelper {

    /**
     * 从卫星数据库中查找文本里提及的卫星。
     */
    fun findSatelliteInText(text: String, app: Application, apiSettings: ApiSettings): SatellitePassTool.HamSatellite? {
        if (text.isBlank()) return null
        return try {
            val tool = SatellitePassTool(app, apiSettings)
            val sats = tool.satelliteDb()
            sats.firstOrNull { sat ->
                text.contains(sat.name, ignoreCase = true) ||
                    (sat.oscar != null && text.contains(sat.oscar, ignoreCase = true))
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 合并预填充 + AI 提取：预填充优先，AI 只补空字段。
     */
    fun mergeContactLog(preFilled: HamContactLog, aiLog: HamContactLog?): HamContactLog {
        if (aiLog == null) return preFilled
        return HamContactLog(
            date = preFilled.date.ifBlank { aiLog.date },
            timeOn = preFilled.timeOn.ifBlank { aiLog.timeOn },
            timeOff = preFilled.timeOff.ifBlank { aiLog.timeOff },
            satName = preFilled.satName.ifBlank { aiLog.satName },
            callsign = preFilled.callsign.ifBlank { aiLog.callsign },
            frequency = preFilled.frequency.ifBlank { aiLog.frequency },
            mode = preFilled.mode.ifBlank { aiLog.mode },
            rstSent = preFilled.rstSent.ifBlank { aiLog.rstSent },
            rstReceived = preFilled.rstReceived.ifBlank { aiLog.rstReceived },
            notes = preFilled.notes.ifBlank { aiLog.notes },
            gridLocator = preFilled.gridLocator.ifBlank { aiLog.gridLocator },
            noradId = preFilled.noradId.ifBlank { aiLog.noradId },
            maxElevation = preFilled.maxElevation.ifBlank { aiLog.maxElevation },
            result = preFilled.result.ifBlank { aiLog.result },
        )
    }

    /**
     * 从 QTH 经纬度计算梅登海格网格（简化版，6 字符）。
     */
    fun ApiSettings.getLastGridLocator(): String? {
        val lat = getLastLatitude() ?: return null
        val lon = getLastLongitude() ?: return null
        return latLonToGrid(lat, lon)
    }

    /**
     * 经纬度转梅登海格 6 字符网格。
     */
    fun latLonToGrid(lat: Float, lon: Float): String {
        // 简化转换：仅做粗略估算，不做完整梅登海格计算
        val latAdj = lat + 90.0
        val lonAdj = lon + 180.0
        val fieldLon = (lonAdj / 20.0).toInt().coerceIn(0, 17)
        val fieldLat = (latAdj / 10.0).toInt().coerceIn(0, 17)
        val squareLon = ((lonAdj % 20.0) / 2.0).toInt().coerceIn(0, 9)
        val squareLat = ((latAdj % 10.0) / 1.0).toInt().coerceIn(0, 9)
        val subsquareLon = ((lonAdj % 2.0) * 12.0).toInt().coerceIn(0, 23)
        val subsquareLat = ((latAdj % 1.0) * 24.0).toInt().coerceIn(0, 23)
        val fieldChars = "ABCDEFGHIJKLMNOPQR"
        val subsquareChars = "abcdefghijklmnopqrstuvwx"
        return "${fieldChars[fieldLon]}${fieldChars[fieldLat]}$squareLon$squareLat${subsquareChars[subsquareLon]}${subsquareChars[subsquareLat]}"
    }

    /**
     * 解析 AI 返回的 JSON 为 HamContactLog。
     */
    fun parseContactLogJson(jsonText: String): HamContactLog? = runCatching {
        val cleaned = jsonText.trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val obj = JSONObject(cleaned)
        HamContactLog(
            date = obj.optString("date"),
            timeOn = obj.optString("timeOn"),
            timeOff = obj.optString("timeOff"),
            satName = obj.optString("satName"),
            callsign = obj.optString("callsign"),
            frequency = obj.optString("frequency"),
            mode = obj.optString("mode"),
            rstSent = obj.optString("rstSent"),
            rstReceived = obj.optString("rstReceived"),
            notes = obj.optString("notes"),
            gridLocator = obj.optString("gridLocator"),
            noradId = obj.optString("noradId"),
            maxElevation = obj.optString("maxElevation"),
            result = obj.optString("result"),
        )
    }.onFailure { DebugLog.w("parseContactLogJson: 解析失败: ${it.message}") }.getOrNull()
}
