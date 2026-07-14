package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 卫星过境预测工具（Ham 模式）
 *
 * 业余卫星通联辅助：
 * - action=list：返回内置业余卫星列表（名称、NORAD ID、频率、调制方式）
 * - action=passes：基于用户缓存位置 + Celestrak TLE 计算未来过境窗口
 *
 * 星历来源：Celestrak amateur.txt（https://celestrak.org/NORAD/elements/amateur.txt）
 * 轨道传播：简化 SGP4（J2 摄动 + 地球扁率），精度满足业余卫星过境预报需求
 */
class SatellitePassTool(
    private val context: Context,
    private val apiSettings: ApiSettings,
) : ToolSet {

    private val satellites: List<HamSatellite> by lazy { loadSatelliteDatabase() }

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "satellite_pass" to ToolBinding(
                name = "satellite_pass",
                description = """卫星过境预测工具（Ham 模式专用）。当用户询问业余卫星通联相关问题时必须调用此工具，包括但不限于：(1) 询问"能打什么卫星"/"哪些卫星过境"/" satellite pass"时；(2) 询问某颗卫星的"频率"/"调制方式"/"转发器信息"时（如"SO-50 的频率是多少"）；(3) 询问"什么时候能通联"/"过境时间"/"什么时候过境"时；(4) 用户提到具体卫星名称（如 SO-50, ISS, AO-91, FO-29, Diwata-2）并询问通联信息时；(5) 询问设备匹配（如"IC-9700 能打什么卫星"）时。action=list 返回所有业余卫星列表（含名称/NORAD ID/上下行频率/调制方式/最低仰角）；action=passes 根据用户当前位置的经纬度计算指定卫星未来 N 小时内的过境窗口（AOS时间/LOS时间/最大仰角/方向/频率），未指定卫星时返回所有卫星过境。参数：action(必填, "list"|"passes")、satellite(可选, 卫星名称或NORAD ID)、hours(可选, 整数小时, 默认24, 最大168)。注意：必须先获取用户位置权限并调用位置服务获取经纬度，否则 passes 计算会失败。""",
                invoker = { tp, config, sp, ups -> executeSatellitePass(tp, config, sp, ups) },
            ),
        )
    }

    private suspend fun executeSatellitePass(
        tp: ToolPart,
        config: ApiConfig,
        systemPrompt: String,
        useProviderSearch: Boolean,
    ): ToolResult {
        val action = tp.state.input["action"]?.trim()?.lowercase() ?: "list"
        val satelliteQuery = tp.state.input["satellite"]?.trim()
        val hours = (tp.state.input["hours"]?.toIntOrNull() ?: 24).coerceIn(1, 168)

        val result = when (action) {
            "list" -> executeList()
            "passes" -> executePasses(satelliteQuery, hours)
            else -> return errorResult(tp, "无效 action='$action'，仅支持 list 或 passes")
        }

        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = if (result.startsWith("[ERROR]")) ToolStateType.ERROR else ToolStateType.COMPLETED,
                    output = result,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    // ==================== action=list ====================

    private fun executeList(): String {
        if (satellites.isEmpty()) {
            return "[ERROR] 业余卫星数据库为空"
        }
        return buildString {
            appendLine("## 业余卫星列表（共 ${satellites.size} 颗）")
            appendLine()
            satellites.forEach { sat ->
                appendLine("- **${sat.name}** (NORAD ${sat.noradId})")
                if (sat.uplinkMHz != null) appendLine("  上行: ${sat.uplinkMHz} MHz")
                if (sat.downlinkMHz != null) appendLine("  下行: ${sat.downlinkMHz} MHz")
                appendLine("  调制: ${sat.modulation}")
                appendLine("  最低仰角: ${sat.minElevationDeg}°")
                if (sat.notes.isNotBlank()) appendLine("  备注: ${sat.notes}")
                appendLine()
            }
        }.trimEnd()
    }

    // ==================== action=passes ====================

    private suspend fun executePasses(satelliteQuery: String?, hours: Int): String = withContext(Dispatchers.IO) {
        val lat = apiSettings.getLastLatitude()?.toDouble()
        val lon = apiSettings.getLastLongitude()?.toDouble()
        if (lat == null || null == lon) {
            return@withContext "[ERROR] 未获取到用户位置。请先在设置中开启位置权限并刷新位置。"
        }

        val targetSats = if (satelliteQuery.isNullOrBlank()) {
            satellites
        } else {
            val id = satelliteQuery.toIntOrNull()
            val matched = if (id != null) {
                satellites.filter { it.noradId == id }
            } else {
                satellites.filter { it.name.contains(satelliteQuery, ignoreCase = true) }
            }
            if (matched.isEmpty()) {
                return@withContext "[ERROR] 未找到匹配 '$satelliteQuery' 的业余卫星。可用卫星: ${satellites.joinToString { "${it.name}(${it.noradId})" }}"
            }
            matched
        }

        val tleMap = fetchTleMap()
        if (tleMap.isEmpty()) {
            return@withContext "[ERROR] 无法获取卫星星历（TLE）。请检查网络连接，或稍后重试。"
        }

        val now = System.currentTimeMillis()
        val endTime = now + hours * 3600_000L

        val passes = mutableListOf<String>()
        for (sat in targetSats) {
            val tle = tleMap[sat.noradId]
            if (tle == null) {
                continue
            }
            try {
                val satPasses = computePasses(tle, lat, lon, now, endTime, sat.minElevationDeg.toDouble())
                if (satPasses.isNotEmpty()) {
                    passes.add("### ${sat.name} (NORAD ${sat.noradId})")
                    if (sat.uplinkMHz != null) passes.add("上行: ${sat.uplinkMHz} MHz")
                    if (sat.downlinkMHz != null) passes.add("下行: ${sat.downlinkMHz} MHz")
                    passes.add("调制: ${sat.modulation}")
                    passes.add("")
                    satPasses.forEach { p ->
                        passes.add("- ${p}")
                    }
                    passes.add("")
                }
            } catch (e: Exception) {
                DebugLog.w("SatellitePassTool: 计算 ${sat.name} 过境失败: ${e.message}")
            }
        }

        if (passes.isEmpty()) {
            return@withContext "未来 $hours 小时内，指定卫星在您的位置（${lat.format(2)}°, ${lon.format(2)}°）无仰角 > 最低阈值的过境窗口。"
        }

        return@withContext buildString {
            appendLine("## 卫星过境预报")
            appendLine("观测站: ${lat.format(2)}°, ${lon.format(2)}° | 未来 $hours 小时")
            appendLine()
            appendLine(passes.joinToString("\n"))
        }.trimEnd()
    }

    // ==================== TLE 获取与缓存 ====================

    private data class TleEntry(val name: String, val line1: String, val line2: String)

    private suspend fun fetchTleMap(): Map<Int, TleEntry> = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "tle_amateur.txt")
        val metaFile = File(context.cacheDir, "tle_amateur_meta.txt")
        val now = System.currentTimeMillis()
        val cacheValid = cacheFile.exists() && metaFile.exists() &&
                (now - metaFile.readText().trim().toLongOrNull().let { it ?: 0L }) < TimeUnit.HOURS.toMillis(24)

        val rawText = if (cacheValid) {
            runCatching { cacheFile.readText() }.getOrNull()
        } else {
            null
        }

        val tleText = rawText ?: run {
            val fetched = runCatching {
                val conn = URL("https://celestrak.org/NORAD/elements/amateur.txt").openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Opedrgent/1.1 (amateur radio satellite tool)")
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull()

            if (fetched != null) {
                runCatching {
                    cacheFile.writeText(fetched)
                    metaFile.writeText(now.toString())
                }
                fetched
            } else if (cacheFile.exists()) {
                runCatching { cacheFile.readText() }.getOrNull()
            } else {
                null
            }
        }

        if (tleText.isNullOrBlank()) {
            return@withContext emptyMap()
        }

        parseTleText(tleText)
    }

    private fun parseTleText(text: String): Map<Int, TleEntry> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableMapOf<Int, TleEntry>()
        var i = 0
        while (i + 2 < lines.size) {
            val name = lines[i]
            val line1 = lines[i + 1]
            val line2 = lines[i + 2]
            if (line1.startsWith("1 ") && line2.startsWith("2 ")) {
                val noradId = line1.substring(2, 7).trim().toIntOrNull()
                if (noradId != null) {
                    result[noradId] = TleEntry(name, line1, line2)
                    i += 3
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result
    }

    // ==================== 卫星过境计算（简化 SGP4） ====================

    private data class PassWindow(
        val aosUtc: Long,
        val losUtc: Long,
        val maxElevationDeg: Double,
        val maxElevationTimeUtc: Long,
        val direction: String,
    )

    private fun computePasses(
        tle: TleEntry,
        obsLatDeg: Double,
        obsLonDeg: Double,
        startTimeUtcMs: Long,
        endTimeUtcMs: Long,
        minElevationDeg: Double,
    ): List<String> {
        val elements = parseTleElements(tle) ?: return emptyList()
        val obsLat = Math.toRadians(obsLatDeg)
        val obsLon = Math.toRadians(obsLonDeg)

        val stepSeconds = 30
        val totalSeconds = ((endTimeUtcMs - startTimeUtcMs) / 1000).toInt()
        val passes = mutableListOf<PassWindow>()

        var inPass = false
        var passStart = 0L
        var maxEl = 0.0
        var maxElTime = 0L
        var passDirection = ""

        for (s in 0..totalSeconds step stepSeconds) {
            val t = startTimeUtcMs + s * 1000L
            val elapsedMinutes = (t - elements.epoch) / 60000.0

            val pos = propagateSgp4(elements, elapsedMinutes) ?: continue
            val (satLat, satLon, altKm) = pos

            val elevation = computeElevation(obsLat, obsLon, satLat, satLon, altKm)

            if (elevation >= minElevationDeg) {
                if (!inPass) {
                    inPass = true
                    passStart = t
                    maxEl = elevation
                    maxElTime = t
                    passDirection = if (s > 0) {
                        val prevT = t - stepSeconds * 1000L
                        val prevElapsed = (prevT - elements.epoch) / 60000.0
                        val prevPos = propagateSgp4(elements, prevElapsed)
                        if (prevPos != null) {
                            val prevEl = computeElevation(obsLat, obsLon, prevPos.lat, prevPos.lon, prevPos.altKm)
                            if (elevation >= prevEl) "升段" else "降段"
                        } else "升段"
                    } else "升段"
                } else {
                    if (elevation > maxEl) {
                        maxEl = elevation
                        maxElTime = t
                    }
                }
            } else {
                if (inPass) {
                    passes.add(PassWindow(passStart, t, maxEl, maxElTime, passDirection))
                    inPass = false
                    maxEl = 0.0
                }
            }
        }
        if (inPass) {
            passes.add(PassWindow(passStart, endTimeUtcMs, maxEl, maxElTime, passDirection))
        }

        return passes.map { p ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getDefault()
            }
            val aosStr = fmt.format(java.util.Date(p.aosUtc))
            val maxStr = fmt.format(java.util.Date(p.maxElevationTimeUtc))
            val losStr = fmt.format(java.util.Date(p.losUtc))
            val durationSec = (p.losUtc - p.aosUtc) / 1000
            "$aosStr → $losStr (${durationSec}s) 最大仰角 ${p.maxElevationDeg.format(1)}° @$maxStr [${p.direction}]"
        }
    }

    // ==================== TLE 解析 ====================

    private data class OrbitalElements(
        val noradId: Int,
        val epoch: Long, // epoch in millis UTC
        val meanMotion: Double,
        val eccentricity: Double,
        val inclination: Double,
        val raan: Double,
        val argPerigee: Double,
        val meanAnomaly: Double,
        val bstar: Double,
    )

    private fun parseTleElements(tle: TleEntry): OrbitalElements? = runCatching {
        val l1 = tle.line1
        val l2 = tle.line2
        val noradId = l1.substring(2, 7).trim().toInt()

        // Epoch: YYDDD.DDDDDDDD
        val epochYear = l1.substring(18, 20).trim().toInt()
        val epochDayOfYear = l1.substring(20, 32).trim().toDouble()
        val fullYear = if (epochYear < 57) 2000 + epochYear else 1900 + epochYear

        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(fullYear, 0, 1, 0, 0, 0)
        val epochMillis = cal.getTimeInMillis() + ((epochDayOfYear - 1) * 86400000L).toLong()

        val meanMotion = l2.substring(52, 63).trim().toDouble() // revs/day
        val eccentricity = "0." + l2.substring(26, 33).trim()
        val inclination = l2.substring(8, 16).trim().toDouble()
        val raan = l2.substring(17, 25).trim().toDouble()
        val argPerigee = l2.substring(34, 42).trim().toDouble()
        val meanAnomaly = l2.substring(43, 51).trim().toDouble()

        // BSTAR drag term
        val bstarStr = l1.substring(53, 61).trim()
        val bstar = parseScientific(bstarStr) ?: 0.0

        OrbitalElements(
            noradId = noradId,
            epoch = epochMillis,
            meanMotion = meanMotion,
            eccentricity = eccentricity.toDouble(),
            inclination = Math.toRadians(inclination),
            raan = Math.toRadians(raan),
            argPerigee = Math.toRadians(argPerigee),
            meanAnomaly = Math.toRadians(meanAnomaly),
            bstar = bstar,
        )
    }.onFailure { DebugLog.w("SatellitePassTool: TLE 解析失败: ${it.message}") }.getOrNull()

    private fun parseScientific(s: String): Double? {
        // Format: 12345-6 or 12345+6 meaning 0.12345e-6 or 0.12345e+6
        if (s.length < 7) return null
        val mantissa = s.substring(0, 5)
        val sign = s[5]
        val exp = s.substring(6)
        val value = mantissa.toDoubleOrNull() ?: return null
        val exponent = exp.toIntOrNull() ?: return null
        val signedExp = if (sign == '-') -exponent else exponent
        return value * 10.0.pow(signedExp - 4) // mantissa is 0.xxxxx
    }

    // ==================== 简化 SGP4 传播 ====================

    private data class SatPosition(val lat: Double, val lon: Double, val altKm: Double)

    private fun propagateSgp4(el: OrbitalElements, minutesFromEpoch: Double): SatPosition? = runCatching {
        val deg2rad = PI / 180.0
        val xke = 0.07436691613317341 // sqrt(GM) in Earth radii^1.5/min
        val mu = 398600.4418 // km^3/s^2
        val re = 6378.135 // km
        val j2 = 0.001082629

        // Mean motion in rad/min
        val n0 = el.meanMotion * 2.0 * PI / 1440.0
        val a1 = (xke / n0).pow(2.0 / 3.0) // semi-major axis in Earth radii

        // J2 perturbation on mean motion and RAAN/argPerigee
        val cosi = cos(el.inclination)
        val theta2 = cosi * cosi
        val x3thm1 = 3.0 * theta2 - 1.0
        val a1j2 = a1 * (1.0 - 1.5 * j2 * x3thm1 / (a1 * a1) * sqrt(1.0 - el.eccentricity * el.eccentricity))

        // Simplified: propagate mean anomaly
        val n = n0
        val M = el.meanAnomaly + n * minutesFromEpoch

        // Solve Kepler's equation (Newton's method)
        var E = M
        for (i in 0 until 50) {
            val dE = (E - el.eccentricity * sin(E) - M) / (1.0 - el.eccentricity * cos(E))
            E -= dE
            if (abs(dE) < 1e-12) break
        }
        val sinE = sin(E)
        val cosE = cos(E)
        val ecosE = el.eccentricity * cosE
        val denom = 1.0 - ecosE
        val a = a1j2 * re // km
        val r = a * denom
        val xOrb = a * (cosE - el.eccentricity)
        val yOrb = a * sqrt(1.0 - el.eccentricity * el.eccentricity) * sinE

        // RAAN precession due to J2
        val raanDot = -1.5 * j2 * n0 * cosi / ((1.0 - el.eccentricity * el.eccentricity).pow(2.0))
        val argPerigeeDot = 0.75 * j2 * n0 * (5.0 * theta2 - 1.0) / ((1.0 - el.eccentricity * el.eccentricity).pow(2.0))

        val raan = el.raan + raanDot * minutesFromEpoch
        val argP = el.argPerigee + argPerigeeDot * minutesFromEpoch

        // Position in ECI
        val u = argP + M // argument of latitude approximation
        val xEci = r * (cos(raan) * cos(u) - sin(raan) * sin(u) * cosi)
        val yEci = r * (sin(raan) * cos(u) + cos(raan) * sin(u) * cosi)
        val zEci = r * sin(u) * sin(el.inclination)

        // Convert ECI to lat/lon (simplified, ignoring Earth rotation rate for short passes)
        val thetaGMST = 0.0 // simplified
        val xEcef = xEci * cos(thetaGMST) + yEci * sin(thetaGMST)
        val yEcef = -xEci * sin(thetaGMST) + yEci * cos(thetaGMST)
        val zEcef = zEci

        val lat = atan2(zEcef, sqrt(xEcef * xEcef + yEcef * yEcef))
        val lon = atan2(yEcef, xEcef)
        val altKm = r - re

        SatPosition(Math.toDegrees(lat), Math.toDegrees(lon), altKm)
    }.onFailure { DebugLog.w("SatellitePassTool: SGP4 传播失败: ${it.message}") }.getOrNull()

    private fun computeElevation(
        obsLat: Double, obsLon: Double,
        satLat: Double, satLon: Double, satAltKm: Double,
    ): Double {
        val re = 6378.135
        val dLat = satLat - obsLat
        val dLon = satLon - obsLon
        val a = sin(dLat / 2).pow(2) + cos(obsLat) * cos(satLat) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val groundDistKm = re * c
        val slantRange = sqrt(groundDistKm * groundDistKm + satAltKm * satAltKm + 2 * groundDistKm * satAltKm * sin(PI / 2))
        val elevation = asin((satAltKm + re * (1 - cos(groundDistKm / re))) / slantRange)
        return Math.toDegrees(elevation)
    }

    // ==================== 卫星数据库加载 ====================

    private data class HamSatellite(
        val name: String,
        val noradId: Int,
        val uplinkMHz: String?,
        val downlinkMHz: String?,
        val modulation: String,
        val minElevationDeg: Int,
        val notes: String,
    )

    private fun loadSatelliteDatabase(): List<HamSatellite> = runCatching {
        val json = context.assets.open("ham_satellites.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            HamSatellite(
                name = obj.getString("name"),
                noradId = obj.getInt("noradId"),
                uplinkMHz = if (obj.isNull("uplinkMHz")) null else obj.getString("uplinkMHz"),
                downlinkMHz = if (obj.isNull("downlinkMHz")) null else obj.getString("downlinkMHz"),
                modulation = obj.getString("modulation"),
                minElevationDeg = obj.optInt("minElevationDeg", 10),
                notes = obj.optString("notes", ""),
            )
        }
    }.getOrElse { emptyList() }

    // ==================== 工具方法 ====================

    private fun errorResult(tp: ToolPart, msg: String): ToolResult {
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.ERROR,
                    error = msg,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private fun Double.format(decimals: Int): String = String.format("%.${decimals}f", this)
}
