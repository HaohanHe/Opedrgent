package top.hsyscn.opedrgent.security

import top.hsyscn.opedrgent.utils.DebugLog

/**
 * 工具调用审批策略（对标 ML-Intern Approval Policy）。
 *
 * ## 设计理念（来自 ML-Intern + KiloCode + OpenCode）
 *
 * AI Agent 不应该拥有无限的自主权。不同操作的风险等级不同：
 * - **SAFE**：读操作、搜索、计算 → 自动批准
 * - **NEEDS_REVIEW**：写文件、发消息、调用 API → 需要用户确认
 * - **DANGEROUS**：删除、执行命令、修改配置 → 需要显式授权
 * - **BLOCKED**：已知的危险操作 → 直接拒绝
 *
 * ## 与 FailClosedValidator 的分工
 * - **FailClosedValidator**（第一道防线）：检测危险格式的输入参数，直接拒绝
 * - **ApprovalPolicy**（第二道防线）：基于工具名和信任级别控制审批流程
 *
 * ## 三层控制
 * 1. **静态规则**：基于工具名和参数模式的白名单/黑名单
 * 2. **动态评估**：LLM 判断操作是否合理（可选）
 * 3. **用户偏好**：用户可设置全局信任级别（YOLO 模式 / 正常 / 严格）
 */

/** 操作风险等级 */
enum class ApprovalRiskLevel(val displayName: String, val color: String) {
    SAFE("安全", "#4CAF50"),
    NEEDS_REVIEW("需确认", "#FF9800"),
    DANGEROUS("危险", "#F44336"),
    BLOCKED("禁止", "#9E9E9E"),
}

/** 全局信任级别（用户设置） */
enum class TrustLevel(val displayName: String) {
    YOLO("全自动"),      // 所有 NEEDS_REVIEW 降为 SAFE
    NORMAL("正常确认"),  // 默认行为
    STRICT("严格模式"),  // 部分 SAFE 升级为 NEEDS_REVIEW
    PARANOID("偏执模式"), // 几乎所有操作都需要确认
}

/**
 * 审批结果。
 *
 * @param approved 是否批准执行
 * @param riskLevel 操作的风险等级
 * @param reason 审批决定的原因说明
 * @param requiresUserConfirmation 是否需要弹窗让用户手动确认
 */
data class ApprovalResult(
    val approved: Boolean,
    val riskLevel: ApprovalRiskLevel,
    val reason: String,
    val requiresUserConfirmation: Boolean = false,
)

/**
 * 审批策略引擎。
 *
 * 纯逻辑层实现，与 FailClosedValidator 互补：
 * - FailClosed 检测危险格式（参数层面的安全校验）
 * - ApprovalPolicy 控制审批流程（工具层面的权限管理）
 *
 * @param trustLevel 全局信任级别，默认为 NORMAL
 */
class ApprovalPolicy(
    var trustLevel: TrustLevel = TrustLevel.NORMAL,
) {

    companion object {
        private const val PREFS_NAME = "opedrgent_approval"
        private const val KEY_TRUST_LEVEL = "trust_level"

        /** 全局单例实例，供 Settings UI 和运行时共同访问 */
        @Volatile
        private var _instance: ApprovalPolicy? = null
        private var _context: android.content.Context? = null

        /** 初始化单例（Application.onCreate 时调用） */
        fun init(context: android.content.Context) {
            val ctx = context.applicationContext
            _context = ctx
            val prefs = ctx.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val saved = prefs.getString(KEY_TRUST_LEVEL, null)
            val level = saved?.let { runCatching { TrustLevel.valueOf(it) }.getOrNull() } ?: TrustLevel.NORMAL
            synchronized(this) {
                _instance ?: ApprovalPolicy(trustLevel = level).also { _instance = it }
            }
        }

        /** 获取全局单例（懒加载） */
        fun getInstance(): ApprovalPolicy =
            _instance ?: synchronized(this) {
                _instance ?: ApprovalPolicy().also { _instance = it }
            }
    }

    /** 工具名 → 风险等级映射（默认规则） */
    private val toolRiskMap = mutableMapOf<String, ApprovalRiskLevel>(
        // SAFE: 只读和信息获取
        "search" to ApprovalRiskLevel.SAFE,
        "read_file" to ApprovalRiskLevel.SAFE,
        "list_files" to ApprovalRiskLevel.SAFE,
        "get_info" to ApprovalRiskLevel.SAFE,
        "calculate" to ApprovalRiskLevel.SAFE,
        "web_fetch" to ApprovalRiskLevel.SAFE,
        "note_query" to ApprovalRiskLevel.SAFE,
        "kb_search" to ApprovalRiskLevel.SAFE,

        // NEEDS_REVIEW: 写入和外部通信
        "write_file" to ApprovalRiskLevel.NEEDS_REVIEW,
        "edit_file" to ApprovalRiskLevel.NEEDS_REVIEW,
        "send_message" to ApprovalRiskLevel.NEEDS_REVIEW,
        "create_note" to ApprovalRiskLevel.NEEDS_REVIEW,
        "delete_note" to ApprovalRiskLevel.NEEDS_REVIEW,
        "api_call" to ApprovalRiskLevel.NEEDS_REVIEW,
        "execute_skill" to ApprovalRiskLevel.NEEDS_REVIEW,

        // DANGEROUS: 破坏性和敏感操作
        "delete_file" to ApprovalRiskLevel.DANGEROUS,
        "execute_command" to ApprovalRiskLevel.DANGEROUS,
        "modify_settings" to ApprovalRiskLevel.DANGEROUS,
        "send_email" to ApprovalRiskLevel.DANGEROUS,
        "share_data" to ApprovalRiskLevel.DANGEROUS,

        // BLOCKED: 绝对禁止
        "rm_rf" to ApprovalRiskLevel.BLOCKED,
        "format_disk" to ApprovalRiskLevel.BLOCKED,
        "sudo" to ApprovalRiskLevel.BLOCKED,
        "curl_pipe_sh" to ApprovalRiskLevel.BLOCKED,
    )

    /** 参数模式黑名单（正则模式 → 原因） */
    private val paramBlacklist = mutableListOf(
        Regex("(?i)(rm|del).*(-rf|--force)") to "递归强制删除",
        Regex("(?i)sudo.*(|sh|bash|python)") to "提权执行脚本",
        Regex("(?i)curl.*\\|\\s*(sh|bash|python|perl)") to "远程代码执行",
        Regex("(?i)format|mkfs") to "格式化磁盘",
        Regex("(?i)>\\s*/dev/sd[a-z]") to "覆盖磁盘分区",
        Regex("(?i)chmod.*777") to "设置全开放权限",
        Regex("(?i)wget.*\\|\\s*sh") to "下载并执行远程脚本",
        Regex("(?i)\\:\\(\\)\\{\\s*\\:\\|\\:\\&\\s*\\}\\;:") to "Fork炸弹",
    )

    // ==================== 核心 API ====================

    /**
     * 评估一个工具调用是否应该被批准。
     *
     * 评估流程：
     * 1. 检查参数黑名单（最高优先级）
     * 2. 获取基础风险等级
     * 3. 应用信任级别调整
     * 4. 生成审批结果
     *
     * @param toolName 工具名称
     * @param params 工具参数（JSON 字符串或 Map）
     * @return 审批结果
     */
    fun evaluate(toolName: String, params: Any? = null): ApprovalResult {
        // Step 1: 检查参数黑名单（最高优先级）
        val paramStr = params?.toString().orEmpty()
        for ((pattern, reason) in paramBlacklist) {
            if (pattern.containsMatchIn(paramStr)) {
                DebugLog.w("ApprovalPolicy: BLOCKED pattern match: $pattern in $toolName")
                return ApprovalResult(false, ApprovalRiskLevel.BLOCKED, "检测到危险操作: $reason")
            }
        }

        // Step 2: 获取基础风险等级
        val baseRisk = toolRiskMap[toolName] ?: ApprovalRiskLevel.NEEDS_REVIEW  // 未知工具默认需确认

        // Step 3: 应用信任级别调整
        val adjustedRisk = adjustForTrustLevel(baseRisk)

        // Step 4: 生成结果
        return when (adjustedRisk) {
            ApprovalRiskLevel.SAFE -> ApprovalResult(true, ApprovalRiskLevel.SAFE, "安全操作，自动批准")
            ApprovalRiskLevel.NEEDS_REVIEW -> ApprovalResult(
                approved = trustLevel == TrustLevel.YOLO,
                riskLevel = adjustedRisk,
                reason = "此操作需要用户确认",
                requiresUserConfirmation = trustLevel != TrustLevel.YOLO,
            )
            ApprovalRiskLevel.DANGEROUS -> ApprovalResult(
                approved = trustLevel == TrustLevel.YOLO,
                riskLevel = adjustedRisk,
                reason = "危险操作，需要显式确认",
                requiresUserConfirmation = trustLevel != TrustLevel.YOLO,
            )
            ApprovalRiskLevel.BLOCKED -> ApprovalResult(false, ApprovalRiskLevel.BLOCKED, "操作被安全策略阻止")
        }
    }

    /**
     * 注册/覆盖工具的风险等级。
     *
     * @param toolName 工具名称
     * @param level 新的风险等级
     */
    fun setToolRisk(toolName: String, level: ApprovalRiskLevel) {
        toolRiskMap[toolName] = level
        DebugLog.i("ApprovalPolicy: $toolName → ${level.displayName}")
    }

    /**
     * 添加参数黑名单规则。
     *
     * @param pattern 正则表达式模式字符串
     * @param reason 匹配时的原因说明
     */
    fun addBlacklistPattern(pattern: String, reason: String) {
        paramBlacklist.add(Regex(pattern, setOf(RegexOption.IGNORE_CASE)) to reason)
    }

    /**
     * 获取所有已注册的工具风险规则。
     *
     * @return 工具名称到风险等级的不可变映射
     */
    fun getToolRules(): Map<String, ApprovalRiskLevel> = toolRiskMap.toMap()

    /**
     * 设置全局信任级别（供 Settings UI 调用）。
     *
     * 切换信任级别后，后续所有工具调用评估都会使用新级别。
     *
     * @param level 新的信任级别
     */
    fun updateTrustLevel(level: TrustLevel) {
        val oldLevel = this.trustLevel
        this.trustLevel = level
        DebugLog.i("ApprovalPolicy: 信任级别从 ${oldLevel.displayName} 切换为 ${level.displayName}")
        // 持久化到 SharedPreferences
        _context?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_TRUST_LEVEL, level.name)?.apply()
    }

    // ==================== 内部方法 ====================

    /**
     * 根据全局信任级别调整风险等级。
     *
     * 调整规则：
     * - YOLO：NEEDS_REVIEW 和 DANGEROUS 都降为 SAFE
     * - NORMAL：保持原样
     * - STRICT：SAFE 升级为 NEEDS_REVIEW
     * - PARANOID：SAFE→NEEDS_REVIEW，NEEDS_REVIEW/DANGEROUS→DANGEROUS
     */
    private fun adjustForTrustLevel(baseRisk: ApprovalRiskLevel): ApprovalRiskLevel {
        return when (trustLevel) {
            TrustLevel.YOLO -> when (baseRisk) {
                ApprovalRiskLevel.NEEDS_REVIEW, ApprovalRiskLevel.DANGEROUS -> ApprovalRiskLevel.SAFE
                else -> baseRisk
            }
            TrustLevel.NORMAL -> baseRisk
            TrustLevel.STRICT -> when (baseRisk) {
                ApprovalRiskLevel.SAFE -> ApprovalRiskLevel.NEEDS_REVIEW  // 安全操作也需要确认
                else -> baseRisk
            }
            TrustLevel.PARANOID -> when (baseRisk) {
                ApprovalRiskLevel.SAFE -> ApprovalRiskLevel.NEEDS_REVIEW
                ApprovalRiskLevel.NEEDS_REVIEW, ApprovalRiskLevel.DANGEROUS -> ApprovalRiskLevel.DANGEROUS
                else -> baseRisk
            }
        }
    }
}
