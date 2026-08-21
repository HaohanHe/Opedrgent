package top.hsyscn.opedrgent.agent

import top.hsyscn.opedrgent.model.ToolPart

data class ResearchPhase(
    val name: String,
    val round: Int = 0,
    val sourcesFound: Int = 0,
    val searchesCompleted: Int = 0,
    val pagesFetched: Int = 0,
    val notesGathered: Int = 0,
    val lastToolName: String? = null,
    val lastToolOutput: String? = null,
    val done: Boolean = false,
    val summary: String? = null,
)

class ResearchState(
    // ★ P0-6 修复：advanceTo 每轮被调用两次（轮次开始 + 工具执行后），导致 roundsUsed 双倍递增。
    // 将阈值从 10 提升到 20，使实际允许 10 轮工具调用。
    val maxRounds: Int = 20,
) {
    var phase: ResearchPhase = ResearchPhase("思考中", round = 0)
        private set

    var roundsUsed: Int = 0
        private set

    /** 连续产生 tool_call 但无正文的轮次数（用于检测搜索死循环） */
    var consecutiveToolOnlyRounds: Int = 0
        private set

    /**
     * 记录一轮结果，更新连续 tool-only 计数。
     * @param hasContent 本轮 LLM 返回中是否包含非空正文
     * @param hasToolCalls 本轮 LLM 返回中是否包含工具调用
     */
    fun recordRoundResult(hasContent: Boolean, hasToolCalls: Boolean) {
        if (!hasContent && hasToolCalls) {
            consecutiveToolOnlyRounds++
        } else {
            consecutiveToolOnlyRounds = 0
        }
    }

    val sourcesFound = mutableSetOf<String>()
    val completedSearches = mutableListOf<String>()
    val fetchedUrls = mutableListOf<String>()
    val gatheredNotes = mutableListOf<String>()
    val allToolParts = mutableListOf<ToolPart>()

    fun advanceTo(newPhase: ResearchPhase) {
        phase = newPhase.copy(round = roundsUsed)
        if (!newPhase.done) {
            roundsUsed++
        }
    }

    fun recordSearch(query: String, resultCount: Int) {
        completedSearches.add(query)
        advanceTo(phase.copy(
            name = if (resultCount > 0) "搜索完成" else "搜索无结果",
            sourcesFound = sourcesFound.size,
            searchesCompleted = completedSearches.size,
            lastToolName = "web_search",
            lastToolOutput = "$resultCount 条结果",
        ))
    }

    fun recordPageFetch(url: String, chars: Int) {
        fetchedUrls.add(url)
        advanceTo(phase.copy(
            name = "读取完成",
            pagesFetched = fetchedUrls.size,
            lastToolName = "read_url",
            lastToolOutput = "$chars 字",
        ))
    }

    fun recordNoToolCalls(responseText: String) {
        advanceTo(phase.copy(
            name = "思考完成",
            done = true,
            summary = responseText.take(100),
        ))
    }

    fun shouldContinue(): Boolean {
        if (phase.done) return false
        if (roundsUsed >= maxRounds) {
            advanceTo(phase.copy(name = "达到最大轮次", done = true))
            return false
        }
        return true
    }

    fun nextPhaseLabel(): String = when {
        phase.done -> "完成"
        phase.name == "思考中" && roundsUsed == 0 -> "深度思考中…"
        phase.name == "思考中" -> "继续深度思考…"
        phase.name == "搜索中" -> "正在搜索: ${phase.lastToolOutput ?: ""}"
        phase.name == "读取中" -> "正在读取: ${phase.lastToolOutput ?: ""}"
        phase.name == "搜索完成" || phase.name == "读取完成" -> "分析中…"
        else -> phase.name
    }
}
