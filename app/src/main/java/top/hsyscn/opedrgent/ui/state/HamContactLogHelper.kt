package top.hsyscn.opedrgent.ui.state

import android.app.Application
import org.json.JSONObject
import top.hsyscn.opedrgent.model.HamContactLog
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.tools.SatellitePassTool
import top.hsyscn.opedrgent.utils.DebugLog
import java.lang.ref.WeakReference

/**
 * 业余卫星通联日志辅助类。
 *
 * 负责从文本中识别卫星、合并预填充与 AI 提取结果、
 * 梅登海格网格换算以及 AI 返回 JSON 的解析。
 */
object HamContactLogHelper {

    /** 缓存 [SatellitePassTool] 实例，避免每次转通联日志都重新解析 assets 卫星数据库。 */
    private var satelliteToolRef: WeakReference<SatellitePassTool>? = null

    private fun satelliteTool(app: Application, apiSettings: ApiSettings): SatellitePassTool {
        return satelliteToolRef?.get()
            ?: SatellitePassTool(app, apiSettings).also { satelliteToolRef = WeakReference(it) }
    }

    /**
     * 从卫星数据库中查找文本里提及的卫星。
     */
    fun findSatelliteInText(text: String, app: Application, apiSettings: ApiSettings): SatellitePassTool.HamSatellite? {
        if (text.isBlank()) return null
        return try {
            val sats = satelliteTool(app, apiSettings).satelliteDb()
            sats.firstOrNull { sat ->
                val aliases = listOfNotNull(
                    sat.name,
                    sat.name.substringBefore("(").trim().takeIf { it != sat.name },
                    sat.oscar,
                )
                aliases.any { alias -> text.containsToken(alias, ignoreCase = true) } ||
                    text.containsToken(sat.noradId.toString(), ignoreCase = true)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 判断 [text] 中是否包含完整的 [token]（ token 前后不能是字母、数字或连字符）。
     * 用于避免 "AO-7" 误匹配到 "AO-73" 这类子串问题。
     */
    private fun String.containsToken(token: String, ignoreCase: Boolean = true): Boolean {
        if (token.isBlank()) return false
        val t = if (ignoreCase) token.lowercase() else token
        val s = if (ignoreCase) this.lowercase() else this
        var start = 0
        while (true) {
            val idx = s.indexOf(t, start, ignoreCase = false)
            if (idx == -1) return false
            val before = if (idx == 0) ' ' else s[idx - 1]
            val after = if (idx + t.length >= s.length) ' ' else s[idx + t.length]
            if (!before.isLetterOrDigit() && before != '-' &&
                !after.isLetterOrDigit() && after != '-'
            ) {
                return true
            }
            start = idx + 1
        }
    }

    /**
     * 合并预填充 + AI 提取：预填充优先，AI 只补空字段。
     *
     * 对 AI 返回的 time/frequency/result 做归一化，避免格式不统一导致 ADIF/CSV 导出失败。
     */
    fun mergeContactLog(preFilled: HamContactLog, aiLog: HamContactLog?): HamContactLog {
        if (aiLog == null) return preFilled
        return HamContactLog(
            // 日期/时间优先采用 AI 从文本中提取的值；提取不到时再用录音开始时间兜底
            date = aiLog.date.takeIf { it.isNotBlank() }?.let { normalizeDate(it) } ?: preFilled.date,
            timeOn = aiLog.timeOn.takeIf { it.isNotBlank() }?.let { normalizeTime(it) } ?: preFilled.timeOn,
            timeOff = aiLog.timeOff.takeIf { it.isNotBlank() }?.let { normalizeTime(it) } ?: preFilled.timeOff,
            // 卫星、频率、调制、网格等可靠信息预填充优先，防止 AI 幻觉
            satName = preFilled.satName.ifBlank { aiLog.satName },
            callsign = aiLog.callsign.takeIf { it.isNotBlank() } ?: preFilled.callsign,
            frequency = preFilled.frequency.ifBlank { normalizeFrequency(aiLog.frequency) },
            mode = preFilled.mode.ifBlank { aiLog.mode },
            rstSent = aiLog.rstSent.takeIf { it.isNotBlank() } ?: preFilled.rstSent,
            rstReceived = aiLog.rstReceived.takeIf { it.isNotBlank() } ?: preFilled.rstReceived,
            notes = aiLog.notes.takeIf { it.isNotBlank() } ?: preFilled.notes,
            gridLocator = preFilled.gridLocator.ifBlank { aiLog.gridLocator },
            noradId = preFilled.noradId.ifBlank { aiLog.noradId },
            maxElevation = aiLog.maxElevation.takeIf { it.isNotBlank() } ?: preFilled.maxElevation,
            result = aiLog.result.takeIf { it.isNotBlank() }?.let { HamContactLog.normalizeResult(it) } ?: preFilled.result,
        )
    }

    /**
     * 时间归一化：尝试把 AI 可能返回的 HHMM/HH:MM/HH:MM:SS 等转为 ADIF 要求的 HHMMSS。
     */
    private fun normalizeTime(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when (digits.length) {
            4 -> digits + "00"
            6 -> digits
            else -> raw.trim()
        }
    }

    /**
     * 频率归一化：去掉单位，只保留数字（MHz）。
     */
    private fun normalizeFrequency(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.toDoubleOrNull() != null) return trimmed
        return Regex("""\d+(\.\d+)?""").find(trimmed)?.value ?: trimmed
    }

    /**
     * 日期归一化：把 AI 可能返回的 2025/7/19、2025年7月19日、2025-7-19 等转为 yyyy-MM-dd。
     * 若只有月/日，则补当前年份。
     */
    private fun normalizeDate(raw: String): String {
        val s = raw.trim()
        // 已经是 yyyy-MM-dd
        if (Regex("""\d{4}-\d{1,2}-\d{1,2}""").matches(s)) {
            val parts = s.split("-")
            return "${parts[0]}-${parts[1].padStart(2, '0')}-${parts[2].padStart(2, '0')}"
        }
        val nums = Regex("""\d+""").findAll(s).map { it.value }.toList()
        return when {
            nums.size >= 3 -> {
                val year = nums[0].padStart(4, '0')
                val month = nums[1].padStart(2, '0')
                val day = nums[2].padStart(2, '0')
                "$year-$month-$day"
            }
            nums.size == 2 -> {
                val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
                val month = nums[0].padStart(2, '0')
                val day = nums[1].padStart(2, '0')
                "$year-$month-$day"
            }
            else -> s
        }
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
     *
     * 标准 Maidenhead 算法：field (18° x 9°) + square (2° x 1°) + subsquare (5' x 2.5')。
     */
    fun latLonToGrid(lat: Float, lon: Float): String {
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
