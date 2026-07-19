package top.hsyscn.opedrgent.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.hsyscn.opedrgent.model.ToolPart
import top.hsyscn.opedrgent.model.ToolStateType
import top.hsyscn.opedrgent.network.HttpClients
import top.hsyscn.opedrgent.network.ToolResult
import top.hsyscn.opedrgent.settings.ApiConfig
import top.hsyscn.opedrgent.settings.ApiSettings
import top.hsyscn.opedrgent.tools.satellite.GeoPos
import top.hsyscn.opedrgent.tools.satellite.OrbitalData
import top.hsyscn.opedrgent.tools.satellite.OrbitalObject
import top.hsyscn.opedrgent.utils.DebugLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * 卫星过境预测工具（Ham 模式）
 *
 * 业余卫星通联辅助：
 * - action=list：返回内置业余卫星列表（名称、NORAD ID、频率、调制方式）
 * - action=search：从 Celestrak 拉取全部活跃卫星 TLE，按名称/NORAD ID 搜索（用于未预置卫星）
 * - action=passes：基于用户缓存位置 + TLE 计算未来过境窗口（支持预置匹配或直接传入 tle_line1/tle_line2）
 *
 * 星历来源（多源 fallback）：Celestrak gp.php?GROUP=amateur（主源）-> AMSAT nasabare（备用）-> Celestrak amateur.txt（旧 URL 兜底）
 * 活跃卫星搜索源：Celestrak gp.php?GROUP=active&FORMAT=tle
 * 过境搜索：粗扫+精扫二阶策略（参考 Look4Sat getLeoPass），1/4 轨道周期回退 + 60s/30s 粗扫 + 500ms 精扫
 * 轨道传播：完整 SGP4/SDP4 算法（移植自 Look4Sat/PREDICT v2.2.5），含 J2/J3/J4 摄动、BSTAR 大气阻力、GMST 地球自转修正
 *
 * ★ 开源合规：SGP4/SDP4 算法移植自 Look4Sat（https://github.com/rt-bishop/Look4Sat），Look4Sat 采用 GPL-3.0 许可证。
 */
class SatellitePassTool(
    private val context: Context,
    private val apiSettings: ApiSettings,
) : ToolSet {

    private val satellites: List<HamSatellite> by lazy { loadSatelliteDatabase() }

    /** 公开访问卫星数据库，供通联日志等模块查询 */
    fun satelliteDb(): List<HamSatellite> = satellites

    override fun getTools(): Map<String, ToolBinding> {
        return mapOf(
            "satellite_pass" to ToolBinding(
                name = "satellite_pass",
                description = """卫星过境预测工具（Ham 模式专用）。

*** 调用优先级（强制）：用户提到 ANY 卫星名称/编号（如 AO-7, Fox-1B, CAS-3H）或询问"能打什么卫星"/"过境时间"/"频率"时，必须 FIRST 调用此工具。禁止在调用此工具前使用 web_search 搜索卫星信息；TLE 星历和卫星参数均由工具内部从 Celestrak 实时获取，外部搜索无法获得过境数据。***

支持三个 action：

1) action=list - 返回内置业余卫星列表（AO-7, SO-50, CAS-3H 等），每颗卫星同时返回 AMSAT 编号和 NORAD ID，以及频率/调制方式。
2) action=search - 从 Celestrak 拉取全部活跃卫星 TLE，按名称或 NORAD ID 搜索。用于用户提到的非预置卫星（如学校参与的立方星、某次通联过的特殊卫星等预置列表里没有的）。
3) action=passes - 根据用户缓存的经纬度 + TLE 计算过境窗口（AOS/LOS/最大仰角/方向/频率/制式）。

*** 两条工作流 ***

A) 预置卫星（list 能查到的，如 AO-7, SO-50, CAS-3H）：
   action=list -> action=passes(satellite=名称或NORAD ID)

B) 未预置卫星（用户提到的非标准名称，list 查不到的，如"北邮参与的那个卫星"/"跟日本学校通联过的"）：
   action=search(satellite=用户提到的名称或NORAD ID) -> 从返回结果拿到 TLE_LINE1/TLE_LINE2 -> action=passes(satellite=名称, tle_line1=..., tle_line2=...)
   注意：search 返回的 TLE 两行必须原样传给 passes 的 tle_line1/tle_line2 参数，不要截断或修改；若 search 返回多条匹配，让用户确认是哪一颗后再传 TLE。

参数说明：
- action(必填, "list"|"search"|"passes")
- satellite(可选, 卫星名称或 NORAD ID；list 不需要；search 必填；passes 可选，传名称走预置匹配，传 tle_line1/tle_line2 时作为显示名)
- hours(可选, 整数小时, 默认24, 最大168；仅 passes 用)
- tle_line1(可选, TLE 第一行, 以 '1 ' 开头；仅 passes 用, 与 tle_line2 配对, 通常来自 search 结果)
- tle_line2(可选, TLE 第二行, 以 '2 ' 开头；仅 passes 用, 与 tle_line1 配对, 通常来自 search 结果)

注意：passes 依赖用户位置缓存，需设置中开启位置权限。""",
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
        val tleLine1 = tp.state.input["tle_line1"]?.trim()
        val tleLine2 = tp.state.input["tle_line2"]?.trim()

        val result = when (action) {
            "list" -> executeList()
            "passes" -> executePasses(satelliteQuery, hours, tleLine1, tleLine2)
            "search" -> {
                if (satelliteQuery.isNullOrBlank()) {
                    "[ERROR] action=search 需要提供 satellite 参数（卫星名称或 NORAD ID）。"
                } else {
                    executeSearch(satelliteQuery)
                }
            }
            else -> "无效 action='$action'，仅支持 list、passes 或 search"
        }

        // 业务结果统一用 COMPLETED 状态返回（包括"数据库为空"/"无位置"/"TLE获取失败"等业务信息），
        // 让 LLM 看到错误说明并转告用户，而不是被 ToolCallGuardrail 当作 FATAL_ERROR 拦截。
        return ToolResult(
            toolPart = tp.copy(
                state = tp.state.copy(
                    status = ToolStateType.COMPLETED,
                    output = result,
                    endTime = System.currentTimeMillis(),
                ),
            ),
        )
    }

    // ==================== action=list ====================

    private fun executeList(): String {
        DebugLog.i("SatellitePassTool: action=list, ${satellites.size} satellites in DB")
        if (satellites.isEmpty()) {
            return "[ERROR] 业余卫星数据库为空"
        }
        return buildString {
            appendLine("## 业余卫星列表（共 ${satellites.size} 颗）")
            appendLine()
            satellites.forEach { sat ->
                // AMSAT 编号（AO-73 等）和 NORAD ID（39444 等）同时返回，模型可任选其一调用 passes
                val oscarStr = if (sat.oscar != null) "${sat.oscar} / " else ""
                appendLine("- **${sat.name}** (${oscarStr}NORAD ${sat.noradId})")
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

    private suspend fun executePasses(
        satelliteQuery: String?,
        hours: Int,
        tleLine1: String?,
        tleLine2: String?,
    ): String = withContext(Dispatchers.IO) {
        val lat = apiSettings.getLastLatitude()?.toDouble()
        val lon = apiSettings.getLastLongitude()?.toDouble()
        if (lat == null || null == lon) {
            return@withContext "[ERROR] 未获取到用户位置。请先在设置中开启位置权限并刷新位置。"
        }

        val now = System.currentTimeMillis()
        val endTime = now + hours * 3600_000L

        // 模式 A：用户直接传入 TLE 两行（通常来自 action=search 的结果），跳过 ham_satellites.json 匹配
        if (!tleLine1.isNullOrBlank() && !tleLine2.isNullOrBlank()) {
            val l1 = tleLine1.trim()
            val l2 = tleLine2.trim()
            if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) {
                return@withContext "[ERROR] TLE 格式错误：tle_line1 应以 '1 ' 开头，tle_line2 应以 '2 ' 开头。"
            }
            val noradId = l1.substring(2, 7).trim().toIntOrNull() ?: -1
            val tleName = satelliteQuery?.takeIf { it.isNotBlank() } ?: "NORAD $noradId"
            val tle = TleEntry(name = tleName, line1 = l1, line2 = l2)
            val minEl = 10.0 // 未预置卫星使用默认最低仰角 10°
            return@withContext try {
                val satPasses = computePasses(tle, lat, lon, now, endTime, minEl)
                if (satPasses.isEmpty()) {
                    "未来 $hours 小时内，${tle.name} 在您的位置（${lat.format(2)}°, ${lon.format(2)}°）无仰角 > ${minEl.format(0)}° 的过境窗口。"
                } else {
                    buildString {
                        appendLine("## 卫星过境预报（用户传入 TLE）")
                        appendLine("卫星: ${tle.name} (NORAD $noradId) | 观测站: ${lat.format(2)}°, ${lon.format(2)}° | 未来 $hours 小时")
                        appendLine("最低仰角阈值: ${minEl.format(0)}°（未预置卫星默认值，无频率/调制信息）")
                        appendLine("时间已转换为本地时区（${java.util.TimeZone.getDefault().id}），原始计算为 UTC。")
                        appendLine()
                        satPasses.forEach { p ->
                            appendLine("- ${p}")
                        }
                    }.trimEnd()
                }
            } catch (e: Exception) {
                DebugLog.w("SatellitePassTool: 计算用户传入 TLE 过境失败: ${e.message}")
                "[ERROR] 计算过境失败: ${e.message}"
            }
        }

        // 模式 B：走原逻辑（ham_satellites.json 匹配 + fetchTleMap）
        val targetSats = if (satelliteQuery.isNullOrBlank()) {
            satellites
        } else {
            val id = satelliteQuery.toIntOrNull()
            val matched = if (id != null) {
                satellites.filter { it.noradId == id }
            } else {
                // 匹配 AMSAT 编号（如 AO-7）或名称（如 Fox-1B）
                satellites.filter {
                    it.name.contains(satelliteQuery, ignoreCase = true) ||
                    (it.oscar != null && it.oscar.equals(satelliteQuery, ignoreCase = true))
                }
            }
            if (matched.isEmpty()) {
                return@withContext "[ERROR] 未找到匹配 '$satelliteQuery' 的预置业余卫星。可用卫星: ${satellites.joinToString { "${it.name}(${it.noradId})" }}。若要查找非预置卫星，请先 action=search 获取 TLE，再以 tle_line1/tle_line2 传入 action=passes。"
            }
            matched
        }

        val tleMap = fetchTleMap()
        if (tleMap.isEmpty()) {
            return@withContext "[ERROR] 无法获取卫星星历（TLE）。请检查网络连接，或稍后重试。"
        }

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
                    if (sat.oscar != null) passes.add("AMSAT: ${sat.oscar}")
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
            appendLine("时间已转换为本地时区（${java.util.TimeZone.getDefault().id}），原始计算为 UTC。")
            appendLine()
            appendLine(passes.joinToString("\n"))
        }.trimEnd()
    }

    // ==================== action=search ====================

    /**
     * 从 celestrak 拉取全部活跃卫星 TLE（GROUP=active），按名称或 NORAD ID 搜索匹配的卫星。
     * 用于查询未预置在 ham_satellites.json 中的卫星（如学校参与的立方星、通联过的特殊卫星等）。
     * 返回匹配卫星的名称、NORAD ID 和 TLE 两行数据，LLM 拿到后可传给 action=passes 计算过境。
     */
    private suspend fun executeSearch(satelliteQuery: String): String = withContext(Dispatchers.IO) {
        DebugLog.i("SatellitePassTool: action=search, query='$satelliteQuery'")
        if (satelliteQuery.isBlank()) {
            return@withContext "[ERROR] search 需要提供 satellite 参数（卫星名称或 NORAD ID）。"
        }

        val activeTleText = fetchActiveTleText()
        if (activeTleText.isNullOrBlank()) {
            return@withContext "[ERROR] 无法获取活跃卫星 TLE 数据。请检查网络连接，或稍后重试。"
        }

        val allTle = parseTleText(activeTleText)
        if (allTle.isEmpty()) {
            return@withContext "[ERROR] 活跃卫星 TLE 数据解析为空。"
        }

        val noradIdQuery = satelliteQuery.trim().toIntOrNull()
        val matched: List<TleEntry> = if (noradIdQuery != null) {
            // 按 NORAD ID 精确匹配
            listOfNotNull(allTle[noradIdQuery])
        } else {
            // 按名称模糊匹配（大小写不敏感）
            allTle.values.filter { it.name.contains(satelliteQuery, ignoreCase = true) }
        }

        if (matched.isEmpty()) {
            return@withContext "[ERROR] 在活跃卫星中未找到匹配 '$satelliteQuery' 的卫星。请确认名称或 NORAD ID 是否正确。"
        }

        // 限制返回数量，避免 TLE 数据过多撑爆上下文
        val limited = matched.take(20)
        buildString {
            appendLine("## 卫星搜索结果（共 ${matched.size} 条匹配，显示前 ${limited.size} 条）")
            appendLine()
            limited.forEach { tle ->
                val noradId = tle.line1.substring(2, 7).trim().toIntOrNull() ?: -1
                appendLine("### ${tle.name} (NORAD $noradId)")
                appendLine("TLE_LINE1: ${tle.line1}")
                appendLine("TLE_LINE2: ${tle.line2}")
                appendLine()
            }
            appendLine("---")
            appendLine("提示：将上述 TLE_LINE1/TLE_LINE2 传入 action=passes（参数 tle_line1/tle_line2）即可计算该卫星的过境窗口。")
        }.trimEnd()
    }

    /**
     * 拉取 celestrak 全部活跃卫星 TLE（GROUP=active），用于 action=search。
     * 使用独立缓存（tle_active.txt，与 amateur 缓存分离，避免相互覆盖），24 小时有效。
     *
     * 若活跃目录拉取失败且无有效缓存，则回退到业余卫星 TLE 缓存（fetchTleMap），
     * 保证在国内网络/弱网环境下仍能搜索常见业余卫星。
     */
    private suspend fun fetchActiveTleText(): String? = withContext(Dispatchers.IO) {
        val cacheFile = File(context.cacheDir, "tle_active.txt")
        val metaFile = File(context.cacheDir, "tle_active_meta.txt")
        val now = System.currentTimeMillis()
        val cacheValid = cacheFile.exists() && metaFile.exists() &&
                (now - metaFile.readText().trim().toLongOrNull().let { it ?: 0L }) < TimeUnit.HOURS.toMillis(24)

        if (cacheValid) {
            runCatching { cacheFile.readText() }.getOrNull()?.let { return@withContext it }
        }

        val url = "https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=tle"
        val fetched = runCatching {
            // 使用 HttpClients.longRunning 复用连接池与超时分层配置（替代裸 HttpURLConnection）
            val req = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Opedrgent/1.1 (amateur radio satellite tool)")
                .build()
            HttpClients.longRunning.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()

        if (!fetched.isNullOrBlank()) {
            runCatching {
                cacheFile.writeText(fetched)
                metaFile.writeText(now.toString())
            }
            return@withContext fetched
        }

        // 拉取失败，回退到过期缓存
        if (cacheFile.exists()) {
            return@withContext runCatching { cacheFile.readText() }.getOrNull()
        }

        // 活跃目录完全不可用，降级到业余卫星缓存（覆盖常见 HAM 卫星）
        val amateurTle = fetchTleMap()
        if (amateurTle.isNotEmpty()) {
            return@withContext amateurTle.values.joinToString("\n") { "${it.name}\n${it.line1}\n${it.line2}" }
        }
        null
    }

    // ==================== TLE 获取与缓存 ====================

    private data class TleEntry(val name: String, val line1: String, val line2: String)

    /**
     * TLE 多源 fallback 列表（依次尝试，第一个成功即用）：
     * 1. celestrak gp.php?GROUP=amateur&FORMAT=tle（主源，推荐）
     * 2. amsat nasabare.txt（AMSAT 备用源）
     * 3. celestrak amateur.txt（旧 URL 兜底）
     */
    private val tleSources = listOf(
        "https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=tle",
        "https://amsat.org/tle/current/nasabare.txt",
        "https://celestrak.org/NORAD/elements/amateur.txt",
    )

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
            // 多源 fallback：依次尝试 tleSources 中的源，第一个成功即用
            val fetched = fetchTleFromSources()

            if (fetched != null) {
                runCatching {
                    cacheFile.writeText(fetched)
                    metaFile.writeText(now.toString())
                }
                fetched
            } else if (cacheFile.exists()) {
                // 所有源都失败，回退到过期缓存
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

    /**
     * 依次尝试 [tleSources] 中的源，返回第一个非空 TLE 文本；全部失败返回 null。
     */
    private fun fetchTleFromSources(): String? {
        for (url in tleSources) {
            val result = runCatching {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Opedrgent/1.1 (amateur radio satellite tool)")
                    .build()
                HttpClients.longRunning.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()

            if (!result.isNullOrBlank()) {
                DebugLog.i("SatellitePassTool: TLE 源获取成功: $url")
                return result
            }
            DebugLog.w("SatellitePassTool: TLE 源失败或为空: $url")
        }
        return null
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

    // ==================== 卫星过境计算（SGP4/SDP4） ====================

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
        val orbitalData = parseTleToOrbitalData(tle) ?: return emptyList()
        // OrbitalObject init 块计算量大（SGP4/SDP4 系数），每个 TLE 只创建一次
        val orbitalObject = orbitalData.getObject()
        val geoPos = GeoPos(latitude = obsLatDeg, longitude = obsLonDeg)

        // 轨道周期（分钟）= 1440 / meanMotion（meanMotion 单位 revs/day）
        // 参考 Look4Sat getLeoPass：用 meanMotion 推算轨道周期，回退 1/4 周期作为搜索起点
        val meanMotion = orbitalData.meanmo.coerceAtLeast(0.0001)
        val orbitalPeriodMin = 1440.0 / meanMotion
        val quarterOrbitMs = (orbitalPeriodMin / 4.0).toLong() * 60L * 1000L

        val passes = mutableListOf<PassWindow>()
        // 步骤 1：回退 1/4 轨道周期作为搜索起点（处理当前正在过境中的边缘情况）
        var cursor = startTimeUtcMs - quarterOrbitMs

        // 防御性迭代上限，防止 SGP4 精度问题或传播持续失败导致死循环
        val maxIterations = 500
        var iterationCount = 0

        while (cursor < endTimeUtcMs && iterationCount < maxIterations) {
            iterationCount++

            // 步骤 2：若当前已在过境中（仰角 >= minElevation），30s 步长前进到 LOS，然后跳 3/4 轨道周期
            val startElev = elevationAt(orbitalObject, geoPos, cursor)
            if (startElev != null && startElev >= minElevationDeg) {
                var safety = 0
                while (cursor < endTimeUtcMs && safety < 10000) {
                    safety++
                    cursor += 30_000L
                    val e = elevationAt(orbitalObject, geoPos, cursor)
                    if (e == null || e < minElevationDeg) break
                }
                cursor += quarterOrbitMs * 3
                continue
            }

            // 步骤 3：粗扫找 AOS（60s 步长，直到仰角 >= minElevation）
            var maxEl = 0.0
            var aosFound = false
            var safety = 0
            while (cursor < endTimeUtcMs && safety < 20000) {
                safety++
                cursor += 60_000L
                val e = elevationAt(orbitalObject, geoPos, cursor)
                if (e == null) continue
                if (e > maxEl) maxEl = e
                if (e >= minElevationDeg) {
                    aosFound = true
                    break
                }
            }
            if (!aosFound || cursor >= endTimeUtcMs) break

            // 步骤 4：精扫细化 AOS（500ms 步长）
            cursor -= 60_000L // 回退一步
            var aosTime = -1L
            safety = 0
            while (cursor < endTimeUtcMs && safety < 200000) {
                safety++
                cursor += 500L
                val e = elevationAt(orbitalObject, geoPos, cursor)
                if (e == null) continue
                if (e > maxEl) maxEl = e
                if (e >= minElevationDeg) {
                    aosTime = cursor
                    break
                }
            }
            if (aosTime < 0) break

            // 步骤 5：粗扫找 LOS（30s 步长），同时追踪最大仰角
            safety = 0
            while (cursor < endTimeUtcMs && safety < 10000) {
                safety++
                cursor += 30_000L
                val e = elevationAt(orbitalObject, geoPos, cursor)
                if (e == null) break
                if (e > maxEl) maxEl = e
                if (e < minElevationDeg) break
            }

            // 步骤 6：精扫细化 LOS（500ms 步长）
            cursor -= 30_000L // 回退一步
            var losTime = -1L
            safety = 0
            while (cursor < endTimeUtcMs && safety < 200000) {
                safety++
                cursor += 500L
                val e = elevationAt(orbitalObject, geoPos, cursor)
                if (e == null) break
                if (e > maxEl) maxEl = e
                if (e < minElevationDeg) {
                    losTime = cursor
                    break
                }
            }
            if (losTime < 0) {
                // 没找到 LOS（到 endTime 仍在过境中，或传播失败），用 endTime 作为 LOS
                losTime = endTimeUtcMs
            }

            // 步骤 7：TCA（最大仰角时刻）= (AOS + LOS) / 2
            val tcaTime = (aosTime + losTime) / 2

            // 只记录 AOS >= startTime 的过境（未来过境）；
            // AOS < startTime 说明是回退期间找到的过去过境，跳过记录但仍从 LOS 继续搜索下一个
            if (aosTime >= startTimeUtcMs) {
                val direction = computePassDirection(orbitalObject, geoPos, aosTime)
                passes.add(
                    PassWindow(
                        aosUtc = aosTime,
                        losUtc = losTime,
                        maxElevationDeg = maxEl,
                        maxElevationTimeUtc = tcaTime,
                        direction = direction,
                    ),
                )
            }

            // 步骤 8：从 LOS + 3/4 轨道周期开始找下一个过境
            cursor = losTime + quarterOrbitMs * 3
        }

        val tzId = java.util.TimeZone.getDefault().id
        return passes.map { p ->
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss 'UTC'Z", java.util.Locale.getDefault()).apply {
                timeZone = java.util.TimeZone.getDefault()
            }
            val aosStr = fmt.format(java.util.Date(p.aosUtc))
            val maxStr = fmt.format(java.util.Date(p.maxElevationTimeUtc))
            val losStr = fmt.format(java.util.Date(p.losUtc))
            val durationSec = (p.losUtc - p.aosUtc) / 1000
            "$aosStr → $losStr (${durationSec}s) 最大仰角 ${p.maxElevationDeg.format(1)}° @$maxStr [$p.direction]（本地时区 $tzId）"
        }
    }

    /**
     * 计算 [timeUtcMs] 时刻的卫星仰角（度），使用完整 SGP4/SDP4 轨道传播。
     * OrbitalObject.getElevation() 返回弧度，此处转为度。
     * 返回 null 表示传播失败或结果无效（NaN/Infinity）。
     */
    private fun elevationAt(
        orbitalObject: OrbitalObject,
        geoPos: GeoPos,
        timeUtcMs: Long,
    ): Double? {
        return try {
            val elevRad = orbitalObject.getElevation(geoPos, timeUtcMs)
            val elevDeg = Math.toDegrees(elevRad)
            if (elevDeg.isNaN() || elevDeg.isInfinite()) null else elevDeg
        } catch (e: Exception) {
            DebugLog.w("SatellitePassTool: getElevation 失败: ${e.message}")
            null
        }
    }

    /**
     * 判断过境方向（升段/降段）：比较 AOS 时刻与前一分钟的仰角。
     */
    private fun computePassDirection(
        orbitalObject: OrbitalObject,
        geoPos: GeoPos,
        aosTime: Long,
    ): String {
        val aosEl = elevationAt(orbitalObject, geoPos, aosTime) ?: return "升段"
        val prevEl = elevationAt(orbitalObject, geoPos, aosTime - 60_000L) ?: return "升段"
        return if (aosEl >= prevEl) "升段" else "降段"
    }

    // ==================== TLE 解析 ====================

    /**
     * 将 TLE 两行字符串解析为 OrbitalData，用于 SGP4/SDP4 轨道传播。
     * 解析逻辑移植自 Look4Sat DataParser.parseTLE()，修正了 BSTAR 指数偏移 bug。
     */
    private fun parseTleToOrbitalData(tle: TleEntry): OrbitalData? = runCatching {
        val line1 = tle.line1
        val line2 = tle.line2
        OrbitalData(
            name = tle.name,
            epoch = line1.substring(18, 32).toDouble(),
            meanmo = line2.substring(52, 63).toDouble(),
            eccn = line2.substring(26, 33).toDouble() / 1e7,
            incl = line2.substring(8, 16).toDouble(),
            raan = line2.substring(17, 25).toDouble(),
            argper = line2.substring(34, 42).toDouble(),
            meanan = line2.substring(43, 51).toDouble(),
            catnum = line1.substring(2, 7).trim().toInt(),
            bstar = parseBstar(line1),
            ndot = line1.substring(33, 43).trim().toDouble()
        )
    }.onFailure { DebugLog.w("SatellitePassTool: TLE 解析失败: ${it.message}") }.getOrNull()

    /**
     * 解析 TLE 第 1 行的 BSTAR 大气阻力项。
     * BSTAR 字段位于 positions 53-60（8 字符），格式 " MMMMMSE"：
     * - position 53: 前导空格
     * - positions 54-58: 5 位尾数（MMMMM）
     * - position 59: 符号（+/-）
     * - position 60: 指数（E，1 位数字）
     * 实际值 = (MMMMM / 100000) * 10^(±E) = MMMMM * 10^(-5 ± E)
     */
    private fun parseBstar(line1: String): Double {
        val mantissa = line1.substring(53, 59).trim().toDoubleOrNull() ?: return 0.0
        val sign = line1[59]
        val exponent = line1.substring(60, 61).toIntOrNull() ?: return 0.0
        val signedExp = if (sign == '-') -exponent else exponent
        return mantissa * 10.0.pow(signedExp - 5)
    }

    // ==================== 卫星数据库加载 ====================

    data class HamSatellite(
        val name: String,
        val noradId: Int,
        val oscar: String?,
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
                oscar = if (obj.isNull("oscar")) null else obj.getString("oscar"),
                uplinkMHz = if (obj.isNull("uplinkMHz")) null else obj.getString("uplinkMHz"),
                downlinkMHz = if (obj.isNull("downlinkMHz")) null else obj.getString("downlinkMHz"),
                modulation = obj.getString("modulation"),
                minElevationDeg = obj.optInt("minElevationDeg", 10),
                notes = obj.optString("notes", ""),
            )
        }
    }.onFailure { e ->
        // 关键修复：之前 getOrElse { emptyList() } 会吞掉 JSON 解析异常，
        // 导致 executeList() 返回"[ERROR] 业余卫星数据库为空"，LLM 误以为没有卫星
        // 而反复 web_search 浪费数分钟。现在必须打印错误日志便于排查。
        DebugLog.e("SatellitePassTool: ham_satellites.json 加载失败: ${e.javaClass.simpleName}: ${e.message}")
    }.getOrDefault(emptyList())

    // ==================== 工具方法 ====================

    private fun Double.format(decimals: Int): String = String.format("%.${decimals}f", this)
}
