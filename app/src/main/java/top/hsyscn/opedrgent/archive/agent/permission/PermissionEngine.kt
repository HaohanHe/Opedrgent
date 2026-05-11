package top.hsyscn.opedrgent.agent.permission

import kotlinx.serialization.Serializable

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
    DEFAULT("Default"),
    ACCEPT_EDITS("Accept Edits"),
    PLAN("Plan Mode"),
    BYPASS("Bypass"),
    YOLO("YOLO"),
}

@Serializable
data class PermissionDecision(
    val allowed: Boolean,
    val behavior: PermissionBehavior,
    val reason: String? = null,
    val source: PermissionSource? = null,
)

@Serializable
data class PermissionRequest(
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
    val toolDescription: String? = null,
    val isMcpTool: Boolean = false,
    val mcpServerName: String? = null,
)

class PermissionEngine(
    private val mode: PermissionMode = PermissionMode.DEFAULT,
) {

    private val rules = mutableListOf<PermissionRule>()
    private val denialTracker = DenialTracker()

    init {
        addDefaultRules()
    }

    private fun addDefaultRules() {
        rules.add(
            PermissionRule(
                source = PermissionSource.DEFAULT,
                behavior = PermissionBehavior.ALLOW,
                ruleValue = RuleValue(toolName = "read_file"),
            ),
        )
        rules.add(
            PermissionRule(
                source = PermissionSource.DEFAULT,
                behavior = PermissionBehavior.ALLOW,
                ruleValue = RuleValue(toolName = "web_search"),
            ),
        )
        rules.add(
            PermissionRule(
                source = PermissionSource.DEFAULT,
                behavior = PermissionBehavior.ALLOW,
                ruleValue = RuleValue(toolName = "fetch_url"),
            ),
        )
        rules.add(
            PermissionRule(
                source = PermissionSource.DEFAULT,
                behavior = PermissionBehavior.ASK,
                ruleValue = RuleValue(toolName = "shell_command"),
            ),
        )
        rules.add(
            PermissionRule(
                source = PermissionSource.DEFAULT,
                behavior = PermissionBehavior.ASK,
                ruleValue = RuleValue(toolName = "write_file"),
            ),
        )
    }

    fun addRule(rule: PermissionRule) {
        rules.add(0, rule)
    }

    fun removeRule(source: PermissionSource, toolName: String) {
        rules.removeAll { it.source == source && it.ruleValue.toolName == toolName }
    }

    fun checkPermission(request: PermissionRequest): PermissionDecision {
        if (mode == PermissionMode.YOLO || mode == PermissionMode.BYPASS) {
            return PermissionDecision(
                allowed = true,
                behavior = PermissionBehavior.ALLOW,
                reason = "Mode: ${mode.title}",
            )
        }

        if (mode == PermissionMode.PLAN) {
            return PermissionDecision(
                allowed = false,
                behavior = PermissionBehavior.DENY,
                reason = "Plan mode: execute via plan, not direct tool call",
            )
        }

        for (rule in rules) {
            if (!matchesRule(rule, request)) continue

            return when (rule.behavior) {
                PermissionBehavior.ALLOW -> {
                    denialTracker.recordSuccess(request.toolName)
                    PermissionDecision(true, PermissionBehavior.ALLOW, null, rule.source)
                }
                PermissionBehavior.DENY -> {
                    denialTracker.recordDenial(request.toolName)
                    PermissionDecision(false, PermissionBehavior.DENY, "Denied by rule", rule.source)
                }
                PermissionBehavior.ASK -> {
                    if (denialTracker.shouldAutoAllow(request.toolName)) {
                        PermissionDecision(true, PermissionBehavior.ALLOW, "Auto-allowed after successful history")
                    } else {
                        PermissionDecision(false, PermissionBehavior.ASK, "Requires user approval", rule.source)
                    }
                }
                PermissionBehavior.PASSTHROUGH -> PermissionDecision(true, PermissionBehavior.ALLOW, "Passthrough")
            }
        }

        return when (mode) {
            PermissionMode.DEFAULT, PermissionMode.ACCEPT_EDITS -> {
                PermissionDecision(false, PermissionBehavior.ASK, "No matching rule; requires approval")
            }
            else -> PermissionDecision(true, PermissionBehavior.ALLOW, "Default allow")
        }
    }

    fun setMode(mode: PermissionMode) {
        DebugLog.i("PermissionEngine: mode changed to ${mode.title}")
    }

    fun getDenialStats(): DenialTracker.Stats = denialTracker.getStats()

    private fun matchesRule(rule: PermissionRule, request: PermissionRequest): Boolean {
        val rv = rule.ruleValue
        if (rv.toolName != "*" && rv.toolName != request.toolName) return false

        if (rv.command != null && request.toolName == "shell_command") {
            val cmd = request.arguments["command"] ?: ""
            if (!cmd.contains(rv.command, ignoreCase = true)) return false
        }

        return true
    }

    private val debugTag = "PermissionEngine"

    private object DebugLog {
        fun i(msg: String) = top.hsyscn.opedrgent.utils.DebugLog.i(msg)
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
            totalDenials = denials.values.sum(),
            byTool = successes.keys.associateWith { tool ->
                ToolStats(
                    successes = successes[tool] ?: 0,
                    denials = denials[tool] ?: 0,
                )
            },
        )
    }

    data class Stats(
        val totalSuccesses: Int,
        val totalDenials: Int,
        val byTool: Map<String, ToolStats>,
    )

    data class ToolStats(
        val successes: Int,
        val denials: Int,
    )
}