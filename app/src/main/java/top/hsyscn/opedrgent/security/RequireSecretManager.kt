package top.hsyscn.opedrgent.security

import android.content.Context
import top.hsyscn.opedrgent.mcp.skills.StandardSkillDefinition
import top.hsyscn.opedrgent.utils.DebugLog

/**
 * Skill Secret 安全管理器（对标 Google Gallery require-secret 机制）。
 *
 * ## 设计目标
 * 当 SKILL.md 声明 `require-secret: true` 时，该 Skill 在执行前需要用户
 * 显式授权提供 API Key / Token 等敏感凭据。本管理器负责：
 * 1. 解析 SKILL.md frontmatter 中的 requires-secret 标记
 * 2. 执行前弹出确认对话框让用户授权
 * 3. 缓存用户的授权决定（App 重启后清除）
 * 4. 与 ApprovalPolicy RiskLevel 体系集成
 *
 * ## Gallery 标准流程
 * ```
 * SKILL.md: require-secret: true
 *   → 用户调用 Skill → checkSecret() → 未缓存 → 弹窗请求授权
 *   → 用户输入/确认 → grantSecret() → 缓存 → 继续执行
 *   → 用户拒绝 → 返回 DENIED → Skill 不执行
 * ```
 *
 * @param context Android Context（用于 SharedPreferences）
 */
class RequireSecretManager(private val context: Context) {

    companion object {
        private const val TAG = "RequireSecretManager"
        private const val PREFS_NAME = "opedrgent_secret_manager"
        /** 已授权的 Skill 名称集合 key */
        private const val KEY_GRANTED_SKILLS = "granted_skills"
        /** 已撤销的 Skill 名称集合 key */
        private const val KEY_REVOKED_SKILLS = "revoked_skills"

        @Volatile
        private var instance: RequireSecretManager? = null

        /**
         * 获取全局单例。
         */
        fun getInstance(context: Context): RequireSecretManager =
            instance ?: synchronized(this) {
                instance ?: RequireSecretManager(context.applicationContext).also { instance = it }
            }

        /**
         * 重置单例（测试用）。
         */
        fun resetInstance() {
            synchronized(this) { instance = null }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── 授权状态缓存（内存级，App 重启清除）──

    /** 本次会话中已获得用户授权的 Skill 名称集合 */
    private val grantedSkills: MutableSet<String>
        get() = prefs.getStringSet(KEY_GRANTED_SKILLS, emptySet())?.toMutableSet() ?: mutableSetOf()

    /** 本次会话中被用户明确撤销的 Skill 名称集合 */
    private val revokedSkills: MutableSet<String>
        get() = prefs.getStringSet(KEY_REVOKED_SKILLS, emptySet())?.toMutableSet() ?: mutableSetOf()

    // ==================== 核心 API ====================

    /**
     * 检查指定 Skill 是否需要 Secret 以及当前授权状态。
     *
     * 返回值：
     * - **GRANTED**：Skill 不需要 secret，或用户已在本会话中授权过
     * - **REQUIRED**：Skill 需要 secret 且尚未授权，应弹出授权对话框
     * - **DENIED**：用户曾明确撤销过此 Skill 的 secret，不应再次弹窗
     *
     * @param skillName 技能名称（metadata.name）
     * @return 授权状态枚举
     */
    fun checkSecret(skillName: String): SecretAuthState {
        val granted = grantedSkills
        val revoked = revokedSkills

        return when {
            skillName in revoked -> {
                DebugLog.i("$TAG: '$skillName' 的 Secret 授权已被用户撤销")
                SecretAuthState.DENIED("您已撤销了此技能的 Secret 授权")
            }
            skillName in granted -> {
                DebugLog.d("$TAG: '$skillName' 已有缓存的 Secret 授权")
                SecretAuthState.GRANTED
            }
            else -> {
                DebugLog.i("$TAG: '$skillName' 需要 Secret 授权")
                SecretAuthState.REQUIRED
            }
        }
    }

    /**
     * 检查 Skill 定义是否需要 Secret，并返回完整授权状态。
     *
     * 封装了「解析 needsSecret + 查询授权状态」两步操作，
     * 供 RunJsTool / ToolExecutor 在执行前统一调用。
     *
     * @param skillDef 完整的 Skill 定义
     * @return 授权状态 + 风险等级
     */
    fun checkSecretForSkill(skillDef: StandardSkillDefinition): SecretCheckResult {
        if (!skillDef.needsSecret) {
            return SecretCheckResult(
                state = SecretAuthState.GRANTED,
                riskLevel = RiskLevel.SAFE,
                description = "此 Skill 不需要 Secret",
                skillName = skillDef.skillName,
                secretDescription = "",
            )
        }

        val authState = checkSecret(skillDef.skillName)

        // 与 ApprovalPolicy RiskLevel 对齐：需要 secret 的操作至少为 NEEDS_REVIEW
        val riskLevel = when (authState) {
            is SecretAuthState.GRANTED -> RiskLevel.NEEDS_REVIEW  // 已授权但仍属敏感操作
            is SecretAuthState.REQUIRED -> RiskLevel.DANGEROUS    // 待授权：危险级
            is SecretAuthState.DENIED -> RiskLevel.BLOCKED       // 被拒：禁止级
        }

        return SecretCheckResult(
            state = authState,
            riskLevel = riskLevel,
            description = when (authState) {
                is SecretAuthState.GRANTED -> "已授权"
                is SecretAuthState.REQUIRED -> "需要用户提供 API Key / Token"
                is SecretAuthState.DENIED -> (authState as SecretAuthState.DENIED).reason
            },
            skillName = skillDef.skillName,
            secretDescription = skillDef.metadata.requireSecretDescription
                .ifBlank { "此技能需要 API Key 或访问令牌才能正常运行" },
        )
    }

    /**
     * 用户同意授权 — 记录到缓存并持久化。
     *
     * 调用时机：用户在弹窗中输入/确认了 Secret 后。
     *
     * @param skillName 技能名称
     * @param secretValue 可选：用户提供的实际 Secret 值（由调用方决定存储位置）
     */
    fun grantSecret(skillName: String, secretValue: String? = null) {
        val granted = grantedSkills.toMutableSet()
        val revoked = revokedSkills.toMutableSet()

        granted.add(skillName)
        revoked.remove(skillName)

        prefs.edit()
            .putStringSet(KEY_GRANTED_SKILLS, granted)
            .putStringSet(KEY_REVOKED_SKILLS, revoked)
            .apply()

        // 如果提供了实际值，也保存一份（加密存储建议后续升级）
        if (!secretValue.isNullOrBlank()) {
            saveSecretValue(skillName, secretValue)
        }

        DebugLog.i("$TAG: 用户已授权 '$skillName' 的 Secret")
    }

    /**
     * 用户撤销授权 — 从缓存移除并记录撤销标记。
     *
     * 撤销后同一会话内不再弹窗询问此 Skill（避免骚扰），
     * App 重启后撤销记录清除，可重新授权。
     *
     * @param skillName 技能名称
     */
    fun revokeSecret(skillName: String) {
        val granted = grantedSkills.toMutableSet()
        val revoked = revokedSkills.toMutableSet()

        granted.remove(skillName)
        revoked.add(skillName)

        prefs.edit()
            .putStringSet(KEY_GRANTED_SKILLS, granted)
            .putStringSet(KEY_REVOKED_SKILLS, revoked)
            .remove("secret_value_$skillName")  // 同时清除已存的值
            .apply()

        DebugLog.i("$TAG: 用户已撤销 '$skillName' 的 Secret 授权")
    }

    /**
     * 获取用户之前为某 Skill 提供的 Secret 值。
     *
     * 注意：当前使用明文 SharedPreferences 存储，
     * 生产环境应迁移至 Android Keystore / EncryptedSharedPreferences。
     *
     * @param skillName 技能名称
     * @return 存储的 Secret 值，不存在则返回 null
     */
    fun getStoredSecret(skillName: String): String? {
        return prefs.getString("secret_value_$skillName", null)
    }

    /**
     * 清除所有缓存状态（App 退出时调用）。
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_GRANTED_SKILLS)
            .remove(KEY_REVOKED_SKILLS)
            .apply()
        DebugLog.d("$TAG: 会话 Secret 缓存已清除")
    }

    /**
     * 获取所有已授权的 Skill 列表（用于设置页面展示）。
     */
    fun getGrantedSkills(): Set<String> = grantedSkills.toSet()

    /**
     * 获取统计信息（用于调试/审计）。
     */
    fun getStats(): SecretStats {
        return SecretStats(
            grantedCount = grantedSkills.size,
            revokedCount = revokedSkills.size,
        )
    }

    // ==================== 内部方法 ====================

    private fun saveSecretValue(skillName: String, value: String) {
        prefs.edit().putString("secret_value_$skillName", value).apply()
        DebugLog.d("$TAG: 已存储 '$skillName' 的 Secret 值（长度: ${value.length}）")
    }

    // ==================== 数据类 ====================

    /**
     * Secret 授权状态。
     */
    sealed class SecretAuthState {
        /** 已授权（不需要弹窗） */
        data object GRANTED : SecretAuthState()

        /** 需要用户授权（应弹窗） */
        data object REQUIRED : SecretAuthState()

        /** 被用户拒绝（不应再弹窗） */
        data class DENIED(val reason: String) : SecretAuthState()
    }

    /**
     * Secret 检查结果（完整信息，供 UI 层和 ToolExecutor 使用）。
     */
    data class SecretCheckResult(
        val state: SecretAuthState,
        val riskLevel: RiskLevel,
        val description: String,
        val skillName: String,
        val secretDescription: String,
    )

    /**
     * 统计数据。
     */
    data class SecretStats(
        val grantedCount: Int,
        val revokedCount: Int,
    )
}
