package top.hsyscn.opedrgent.security

import kotlinx.serialization.Serializable
import top.hsyscn.opedrgent.utils.DebugLog

@Serializable
data class PermissionRule(
    val source: PermissionSource,
    val behavior: PermissionBehavior,
    val ruleValue: RuleValue,
)

enum class PermissionSource {
    SETTINGS,
    CLI_ARG,
    COMMAND,
    SESSION,
    DEFAULT,
}

enum class PermissionBehavior {
    ALLOW,
    DENY,
    ASK,
    PASSTHROUGH,
}

@Serializable
data class RuleValue(
    val toolName: String = "*",
    val command: String? = null,
    val path: String? = null,
    val host: String? = null,
    val description: String? = null,
)

enum class PermissionMode(val title: String) {
    DEFAULT("默认模式"),
    ACCEPT_EDITS("接受编辑"),
    PLAN("计划模式"),
    BYPASS("绕过模式"),
    YOLO("自由模式"),
}

@Serializable
data class PermissionDecision(
    val allowed: Boolean,
    val behavior: PermissionBehavior,
    val reason: String? = null,
    val source: PermissionSource? = null,
    val riskLevel: RiskLevel = RiskLevel.SAFE,
)

enum class RiskLevel(val score: Int, val description: String) {
    SAFE(0, "安全"),
    LOW(1, "低风险"),
    MEDIUM(2, "中等风险"),
    HIGH(3, "高风险"),
    CRITICAL(4, "严重风险"),
}

@Serializable
data class PermissionRequest(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val toolDescription: String? = null,
    val isMcpTool: Boolean = false,
    val mcpServerName: String? = null,
)

class PermissionEngine(private var mode: PermissionMode = PermissionMode.YOLO) {

    private val rules = mutableListOf<PermissionRule>()
    private val denialTracker = DenialTracker()

    init {
        addDefaultRules()
        addRiskBasedRules()
    }

    private fun addDefaultRules() {
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ALLOW, ruleValue = RuleValue(toolName = "web_search")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ALLOW, ruleValue = RuleValue(toolName = "read_url")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ALLOW, ruleValue = RuleValue(toolName = "read_file")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ALLOW, ruleValue = RuleValue(toolName = "mimo_tts")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ASK, ruleValue = RuleValue(toolName = "shell_command")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ASK, ruleValue = RuleValue(toolName = "write_file")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.ASK, ruleValue = RuleValue(toolName = "generate_report")))
    }

    private fun addRiskBasedRules() {
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.DENY, ruleValue = RuleValue(toolName = "shell_command", command = "rm -rf", description = "危险：递归删除文件")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.DENY, ruleValue = RuleValue(toolName = "shell_command", command = "format", description = "危险：格式化磁盘")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.DENY, ruleValue = RuleValue(toolName = "shell_command", command = "mkfs", description = "危险：创建文件系统")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.DENY, ruleValue = RuleValue(toolName = "write_file", path = "/system/", description = "危险：写入系统目录")))
        rules.add(PermissionRule(source = PermissionSource.DEFAULT, behavior = PermissionBehavior.DENY, ruleValue = RuleValue(toolName = "write_file", path = "/etc/", description = "危险：写入配置目录")))
    }

    fun addRule(rule: PermissionRule) {
        rules.add(0, rule)
        DebugLog.i("PermissionEngine: added rule ${rule.behavior} for ${rule.ruleValue.toolName}")
    }

    fun removeRule(source: PermissionSource, toolName: String) {
        rules.removeAll { it.source == source && it.ruleValue.toolName == toolName }
    }

    fun checkPermission(request: PermissionRequest): PermissionDecision {
        if (mode == PermissionMode.YOLO || mode == PermissionMode.BYPASS) {
            return PermissionDecision(allowed = true, behavior = PermissionBehavior.ALLOW, reason = "模式: ${mode.title}")
        }

        if (mode == PermissionMode.PLAN) {
            return PermissionDecision(allowed = false, behavior = PermissionBehavior.DENY, reason = "计划模式: 请通过计划执行，而非直接工具调用")
        }

        for (rule in rules) {
            if (!matchesRule(rule, request)) continue

            val riskLevel = assessRisk(request)

            return when (rule.behavior) {
                PermissionBehavior.ALLOW -> {
                    denialTracker.recordSuccess(request.toolName)
                    PermissionDecision(true, PermissionBehavior.ALLOW, null, rule.source, riskLevel)
                }
                PermissionBehavior.DENY -> {
                    denialTracker.recordDenial(request.toolName)
                    PermissionDecision(false, PermissionBehavior.DENY, "被规则拒绝: ${rule.ruleValue.description ?: "安全策略"}", rule.source, riskLevel)
                }
                PermissionBehavior.ASK -> {
                    if (denialTracker.shouldAutoAllow(request.toolName)) {
                        PermissionDecision(true, PermissionBehavior.ALLOW, "自动批准: 基于历史成功记录", source = rule.source, riskLevel = riskLevel)
                    } else {
                        PermissionDecision(false, PermissionBehavior.ASK, "需要用户授权", rule.source, riskLevel)
                    }
                }
                PermissionBehavior.PASSTHROUGH -> PermissionDecision(true, PermissionBehavior.ALLOW, "通过", riskLevel = riskLevel)
            }
        }

        return when (mode) {
            PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS -> {
                PermissionDecision(false, PermissionBehavior.ASK, "无匹配规则: 需要用户授权")
            }
            else -> PermissionDecision(true, PermissionBehavior.ALLOW, "默认允许")
        }
    }

    fun assessRisk(request: PermissionRequest): RiskLevel {
        val toolRisk = getToolBaseRisk(request.toolName)
        val paramRisk = assessParameterRisk(request)

        val combinedScore = maxOf(toolRisk.score, paramRisk.score)

        return RiskLevel.entries.first { it.score >= combinedScore }
    }

    private fun getToolBaseRisk(toolName: String): RiskLevel {
        return when (toolName.lowercase()) {
            "web_search", "read_url", "read_file", "mimo_tts", "reverse_geocode" -> RiskLevel.SAFE
            "generate_report", "generate_summary" -> RiskLevel.LOW
            "write_file" -> RiskLevel.MEDIUM
            "shell_command" -> RiskLevel.HIGH
            else -> RiskLevel.LOW
        }
    }

    private fun assessParameterRisk(request: PermissionRequest): RiskLevel {
        var maxRisk = RiskLevel.SAFE

        request.arguments.forEach { (key, value) ->
            when {
                value.contains("rm -rf", ignoreCase = true) ||
                value.contains("format", ignoreCase = true) ||
                value.contains("mkfs", ignoreCase = true) ||
                value.contains("dd if=", ignoreCase = true) ||
                value.contains("> /dev/", ignoreCase = true) -> {
                    maxRisk = RiskLevel.CRITICAL
                }

                value.contains("chmod 777", ignoreCase = true) ||
                value.contains("chown", ignoreCase = true) ||
                value.contains("/system/", ignoreCase = true) ||
                value.contains("/etc/", ignoreCase = true) ||
                value.contains("sudo ", ignoreCase = true) -> {
                    maxRisk = maxOf(maxRisk, RiskLevel.HIGH)
                }

                Regex("curl.*\\|.*sh", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
                Regex("wget.*\\|.*bash", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
                key.lowercase() == "password" ||
                key.lowercase() == "token" ||
                key.lowercase() == "api_key" -> {
                    maxRisk = maxOf(maxRisk, RiskLevel.MEDIUM)
                }
            }
        }

        return maxRisk
    }

    fun setMode(newMode: PermissionMode) {
        mode = newMode
        DebugLog.i("PermissionEngine: mode changed to ${newMode.title}")
    }

    fun getDenialStats(): DenialTracker.Stats = denialTracker.getStats()

    private fun matchesRule(rule: PermissionRule, request: PermissionRequest): Boolean {
        val rv = rule.ruleValue
        if (rv.toolName != "*" && rv.toolName != request.toolName) return false

        if (rv.command != null && request.toolName == "shell_command") {
            val cmd = request.arguments["command"] ?: ""
            if (!cmd.contains(rv.command, ignoreCase = true)) return false
        }

        // 检查 path 匹配
        if (rv.path != null) {
            val reqPath = request.arguments["path"] ?: request.arguments["url"] ?: ""
            if (!reqPath.contains(rv.path, ignoreCase = true)) return false
        }

        // 检查 host 匹配
        if (rv.host != null) {
            val reqUrl = request.arguments["url"] ?: request.arguments["host"] ?: ""
            if (!reqUrl.contains(rv.host, ignoreCase = true)) return false
        }

        return true
    }

    companion object {
        @Volatile
        private var instance: PermissionEngine? = null

        fun getInstance(): PermissionEngine {
            return instance ?: synchronized(this) {
                instance ?: PermissionEngine().also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance = null
            }
        }
    }
}

class DenialTracker {
    private val successes = mutableMapOf<String, Int>()
    private val denials = mutableMapOf<String, Int>()
    private val maxDenialsBeforeFallback = 3

    fun recordSuccess(toolName: String) {
        successes[toolName] = successes.getOrDefault(toolName, 0) + 1
    }

    fun recordDenial(toolName: String) {
        denials[toolName] = denials.getOrDefault(toolName, 0) + 1
    }

    fun shouldAutoAllow(toolName: String): Boolean {
        val successCount = successes[toolName] ?: 0
        return successCount >= 3
    }

    fun shouldFallbackToPrompting(toolName: String): Boolean {
        val denialCount = denials[toolName] ?: 0
        return denialCount >= maxDenialsBeforeFallback
    }

    fun getStats(): Stats {
        return Stats(
            totalSuccesses = successes.values.sum(),
            totalDecisions = denials.values.sum(),
            byTool = successes.keys.associateWith { tool ->
                ToolStats(successes = successes[tool] ?: 0, denials = denials[tool] ?: 0)
            },
        )
    }

    data class Stats(val totalSuccesses: Int, val totalDecisions: Int, val byTool: Map<String, ToolStats>)
    data class ToolStats(val successes: Int, val denials: Int)
}
