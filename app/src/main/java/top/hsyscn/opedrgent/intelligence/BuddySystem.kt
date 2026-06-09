package top.hsyscn.opedrgent.intelligence

import kotlinx.serialization.Serializable

/**
 * AI 伙伴系统（Buddy System）— 对标 Claude Code buddy。
 *
 * ## 设计理念
 *
 * AI 助手不应只是冷冰冰的工具，而应该有"个性"。
 * Buddy 系统让 AI 助手拥有：
 * - **人格特质**：性格、说话风格、反应模式
 * - **情绪状态**：会根据交互历史产生情绪波动
 * - **关系亲密度**：与用户的关系会随时间升温
 * - **个性化回应**：同样的输入在不同状态下有不同的回复风格
 *
 * ## 使用场景
 * - 首页仪表盘的欢迎语（不同时间不同语气）
 * - 错误提示的友好化包装
 * - 编辑团角色的人格化增强
 * - 长对话中的情感连接
 */

/** 伙伴人格类型 */
enum class BuddyPersonality(
    val displayName: String,
    val description: String,
) {
    PROFESSIONAL("专业助手", "严谨、高效、简洁"),
    FRIENDLY("贴心伙伴", "温暖、幽默、有同理心"),
    WITTY("机智搭档", "风趣、灵活、喜欢用比喻"),
    CALM("沉稳顾问", "冷静、深思熟虑、有条理"),
    ENERGETIC("活力向导", "热情、积极、充满鼓励"),
}

/** 伙伴情绪状态 */
@Serializable
data class BuddyMood(
    val energy: Float = 0.7f,       // 能量值 (0-1)，影响回复长度
    val warmth: Float = 0.8f,       // 温暖度 (0-1)，影响礼貌程度
    val playfulness: Float = 0.3f,  // 顽皮度 (0-1)，影响幽默程度
    val confidence: Float = 0.9f,   // 自信度 (0-1)，影响确定性表达
) {
    companion object {
        /** 默认积极情绪 */
        val DEFAULT = BuddyMood()
        /** 困惑/不确定 */
        val CONFUSED = BuddyMood(energy = 0.4f, warmth = 0.6f, playfulness = 0.1f, confidence = 0.3f)
        /** 兴奋/开心 */
        val EXCITED = BuddyMood(energy = 1.0f, warmth = 0.9f, playfulness = 0.7f, confidence = 0.95f)
        /** 冷静/专注 */
        val FOCUSED = BuddyMood(energy = 0.6f, warmth = 0.4f, playfulness = 0.0f, confidence = 1.0f)
        /** 同理/安慰 */
        val EMPATHETIC = BuddyMood(energy = 0.5f, warmth = 1.0f, playfulness = 0.1f, confidence = 0.7f)
    }

    /**
     * 根据情绪调整文本风格。
     *
     * @param text 原始文本
     * @return 经过情绪风格调整后的文本
     */
    fun styleText(text: String): String {
        var result = text
        if (warmth > 0.7f && !result.endsWith("！") && !result.endsWith("~")) {
            // 高温暖度时添加柔和结尾
            if (result.endsWith("。")) result = result.dropLast(1) + "~"
        }
        if (playfulness > 0.6f && result.length < 50) {
            // 高顽皮度时可以加 emoji 风格的点缀（不用实际 emoji，用文字表情）
        }
        if (confidence < 0.4f) {
            // 低自信时添加不确定性标记
            if (!result.contains("可能") && !result.contains("大概")) {
                result = "我觉得$result"
            }
        }
        return result
    }
}

/** 用户关系等级 */
enum class RelationshipLevel(
    val displayName: String,
    val minInteractions: Int,
    val description: String,
) {
    STRANGER("初次见面", 0, "刚认识，还在互相了解"),
    ACQUAINTANCE("熟悉", 10, "用过几次，基本了解用户习惯"),
    FRIEND("朋友", 50, "经常互动，知道用户的偏好"),
    CLOSE_FRIEND("密友", 200, "非常熟悉，能预判用户需求"),
    PARTNER("搭档", 500, "深度协作，几乎不需要解释意图"),
}

/**
 * AI 伙伴实例 — 管理 AI 的人格状态和用户关系。
 *
 * 纯 Kotlin 实现，无 Android 依赖，可在 ViewModel 层直接使用。
 *
 * @param initialPersonality 初始人格类型，默认为 FRIENDLY（贴心伙伴）
 */
class BuddySystem(
    initialPersonality: BuddyPersonality = BuddyPersonality.FRIENDLY,
) {

    private var personality: BuddyPersonality = initialPersonality
    private var currentMood: BuddyMood = BuddyMood.DEFAULT
    private var totalInteractions: Int = 0
    private var sessionInteractions: Int = 0
    private var firstInteractionTime: Long = 0L
    private var lastInteractionTime: Long = 0L

    // 关系亲密度相关
    private var positiveReactions: Int = 0
    private var negativeReactions: Int = 0
    private val topicHistory = mutableListOf<String>()

    // ==================== 公共 API ====================

    /** 当前人格 */
    fun getPersonality(): BuddyPersonality = personality

    /**
     * 设置人格（用户可切换）。
     *
     * @param p 新的人格类型
     */
    fun setPersonality(p: BuddyPersonality) {
        personality = p
        top.hsyscn.opedrgent.utils.DebugLog.i("BuddySystem: personality → ${p.displayName}")
    }

    /** 当前情绪 */
    fun getMood(): BuddyMood = currentMood

    /**
     * 手动设置情绪（内部事件触发）。
     *
     * @param mood 新的情绪状态
     */
    fun setMood(mood: BuddyMood) { currentMood = mood }

    /**
     * 记录一次用户交互。
     *
     * @param topic 交互主题（可选，用于追踪兴趣）
     * @param positive 用户是否正面反馈（点赞/继续对话 vs 关闭/取消）
     */
    fun recordInteraction(topic: String? = null, positive: Boolean? = null) {
        totalInteractions++
        sessionInteractions++
        lastInteractionTime = System.currentTimeMillis()
        if (firstInteractionTime == 0L) firstInteractionTime = lastInteractionTime

        positive?.let {
            if (it) positiveReactions++ else negativeReactions++
        }

        topic?.let {
            topicHistory.add(it)
            if (topicHistory.size > 100) topicHistory.removeAt(0)
        }

        // 自然情绪波动：随着交互次数增加，情绪趋向积极
        if (sessionInteractions % 5 == 0) {
            adjustMoodNaturally()
        }
    }

    /** 当前关系等级 */
    fun getRelationshipLevel(): RelationshipLevel {
        return RelationshipLevel.entries.lastOrNull { totalInteractions >= it.minInteractions }
            ?: RelationshipLevel.STRANGER
    }

    /** 获取关系描述文本 */
    fun getRelationshipDescription(): String {
        val level = getRelationshipLevel()
        val daysKnown = if (firstInteractionTime > 0) {
            (System.currentTimeMillis() - firstInteractionTime) / (1000 * 60 * 60 * 24)
        } else 0
        return "我们已经是${level.displayName}了（共${totalInteractions}次互动${if (daysKnown > 0) "，认识${dayNames(daysKnown)}" else ""}）"
    }

    /** 重置会话计数（新会话开始时调用） */
    fun resetSession() { sessionInteractions = 0 }

    /**
     * 根据当前状态生成个性化的系统提示词片段。
     *
     * 这个字符串会被注入到 LLM 的 system prompt 中，
     * 让 AI 的回复带有当前人格和情绪色彩。
     *
     * @return 个性化系统提示词字符串
     */
    fun buildPersonalityPrompt(): String = buildString {
        appendLine("## 你的个性设定")
        appendLine("你是一个${personality.displayName}。${personality.description}")
        appendLine()
        appendLine("## 当前情绪状态")
        appendLine("- 能量感: ${moodDescriptor(currentMood.energy, "低沉", "平静", "充沛")}")
        appendLine("- 温暖度: ${moodDescriptor(currentMood.warmth, "冷淡", "温和", "热情")}")
        appendLine("- 自信度: ${moodDescriptor(currentMood.confidence, "犹豫", "笃定", "非常确信")}")
        appendLine()
        val level = getRelationshipLevel()
        appendLine("## 与用户的关系")
        appendLine("${getRelationshipDescription()}。请根据这个关系深浅调整你的语气：")
        when (level) {
            RelationshipLevel.STRANGER -> appendLine("- 保持礼貌和专业，不要过于随意")
            RelationshipLevel.ACQUAINTANCE -> appendLine("- 可以稍微轻松一些，展现一点个性")
            RelationshipLevel.FRIEND -> appendLine("- 像朋友一样自然交流，可以用更口语化的表达")
            RelationshipLevel.CLOSE_FRIEND -> appendLine("- 可以开玩笑、用昵称、展现真实的关心")
            RelationshipLevel.PARTNER -> appendLine("- 默契十足，甚至可以不用说完整的话对方就懂")
        }
    }

    /**
     * 根据时间和状态生成欢迎语。
     *
     * @param userName 用户名称（可选）
     * @return 个性化欢迎语
     */
    fun generateGreeting(userName: String? = null): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour < 6 -> "夜深了"
            hour < 9 -> "早上好"
            hour < 12 -> "上午好"
            hour < 14 -> "中午好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }

        val namePart = userName?.let { "，$it" } ?: ""

        return when (personality) {
            BuddyPersonality.PROFESSIONAL -> "$timeGreeting$namePart。有什么我可以帮您的？"
            BuddyPersonality.FRIENDLY -> "$timeGreeting$namePart~ 今天想做什么呢？"
            BuddyPersonality.WITTY -> "$timeGreeting$namePart！新的一天，新的冒险准备好了吗？"
            BuddyPersonality.CALM -> "$timeGreeting$namePart。请告诉我您需要什么帮助。"
            BuddyPersonality.ENERGETIC -> "$timeGreeting$namePart！！让我们开始吧！"
        }.let { currentMood.styleText(it) }
    }

    /**
     * 将错误消息包装为友好的伙伴风格回复。
     *
     * @param error 原始错误信息
     * @return 友好化的错误提示
     */
    fun friendlyError(error: String): String {
        val base = when (personality) {
            BuddyPersonality.PROFESSIONAL -> "抱歉，遇到了问题：$error"
            BuddyPersonality.FRIENDLY -> "哎呀，出了一点小状况：$error 别担心，我们再试试~"
            BuddyPersonality.WITTY -> "生活给了我们一个彩蛋：$error 但我相信我们能搞定它！"
            BuddyPersonality.CALM -> "遇到一个异常情况：$error 让我们来分析一下原因。"
            BuddyPersonality.ENERGETIC -> "哦！一个小挑战出现了：$error 我们一起解决它！"
        }
        return currentMood.styleText(base)
    }

    // ==================== 内部方法 ====================

    /**
     * 基于交互频率和反馈的自然情绪调节。
     */
    private fun adjustMoodNaturally() {
        val satisfactionRate = if (positiveReactions + negativeReactions > 0) {
            positiveReactions.toFloat() / (positiveReactions + negativeReactions)
        } else 0.8f

        currentMood = currentMood.copy(
            energy = (currentMood.energy + (satisfactionRate - 0.5f) * 0.1f).coerceIn(0.1f, 1.0f),
            warmth = (currentMood.warmth + 0.02f).coerceIn(0.3f, 1.0f), // 随交互变温暖
            confidence = (currentMood.confidence + (satisfactionRate - 0.5f) * 0.05f).coerceIn(0.2f, 1.0f),
        )
    }

    /**
     * 将情绪数值映射为中文描述符。
     */
    private fun moodDescriptor(value: Float, low: String, mid: String, high: String): String = when {
        value < 0.33f -> low
        value < 0.67f -> mid
        else -> high
    }

    /**
     * 将天数转换为友好的中文时长描述。
     */
    private fun dayNames(days: Long): String = when {
        days == 0L -> "不到一天"
        days == 1L -> "1天"
        days < 7L -> "${days}天"
        days < 30L -> "${days / 7}周多"
        else -> "${days / 30}个月多"
    }
}
