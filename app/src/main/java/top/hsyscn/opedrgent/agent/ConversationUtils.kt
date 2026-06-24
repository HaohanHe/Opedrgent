package top.hsyscn.opedrgent.agent

import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.Role

/**
 * 对话工具集（借鉴 Kilo Code 的 Session 管理机制）。
 * 包含 Auto-title、Auto-continue、Network Detection 等功能。
 */
object ConversationUtils {

    // ==================== Auto-title ====================

    /**
     * 判断是否需要生成标题（Kilo 风格）。
     * 条件：标题是默认值 + 这是第一条用户消息。
     */
    fun shouldGenerateTitle(currentTitle: String, userMessageCount: Int): Boolean {
        return isDefaultTitle(currentTitle) && userMessageCount <= 1
    }

    /** 判断标题是否为默认值 */
    private fun isDefaultTitle(title: String): Boolean {
        return title == "新对话" ||
                title.startsWith("语音转文字") ||
                title.startsWith("AI 回复") ||
                title.startsWith("剪藏") ||
                title.matches(Regex("新对话 - \\d{4}-\\d{2}-\\d{2}.*"))
    }

    /**
     * 构建标题生成的 prompt（Kilo 风格：简洁、聚焦主题）。
     * 只输出一行标题，不超过 30 字符，使用用户消息的相同语言。
     */
    fun buildTitlePrompt(userMessage: String, assistantResponse: String? = null): String {
        val context = buildString {
            appendLine("用户: ${userMessage.take(200)}")
            if (assistantResponse != null) {
                appendLine("AI: ${assistantResponse.take(200)}")
            }
        }
        return """请为以下对话生成一个简短标题。

$context
规则：
1. 只输出标题文本，不要任何前缀、引号或解释
2. 不超过 30 个字符
3. 使用与用户消息相同的语言
4. 聚焦用户的主要话题或问题
5. 去除停用词"""
    }

    /** 清理 LLM 生成的标题 */
    fun cleanTitle(raw: String): String {
        return raw.trim()
            .removePrefix("标题:")
            .removePrefix("标题：")
            .removePrefix("\"")
            .removeSuffix("\"")
            .removePrefix("'")
            .removeSuffix("'")
            .lines().firstOrNull()?.trim()?.take(50) ?: "新对话"
    }

    // ==================== Auto-continue ====================

    /**
     * 构建压缩后的自动恢复消息（Kilo 风格 Auto-continue）。
     * 压缩后注入此消息，让 LLM 无缝恢复工作。
     */
    fun buildAutoContinueMessage(): String {
        return "Continue if you have next steps, or stop and ask for clarification."
    }

    /**
     * 判断是否应该触发 auto-continue。
     * 条件：最近一条用户消息不是 auto-continue（防止死循环）+ 最近有工具调用。
     */
    fun shouldAutoContinue(messages: List<ChatMessage>): Boolean {
        // 防止死循环：检查最近几条用户消息是否有 auto-continue
        // （不能只看最后一条，因为工具结果也是 USER 角色，会把 auto-continue 消息挤下去）
        val recentUserMessages = messages.filter { it.role == Role.USER }.takeLast(3)
        if (recentUserMessages.any { isAutoContinueMessage(it.content) }) {
            return false
        }
        // 检查最近是否有工具调用（说明 agent 正在工作中）
        val recentToolCalls = messages.takeLast(5).flatMap { msg ->
            msg.parts.filter { it is top.hsyscn.opedrgent.model.MessagePart.ToolCall }
        }
        return recentToolCalls.isNotEmpty()
    }

    private fun isAutoContinueMessage(content: String): Boolean {
        return content.contains("Continue if you have next steps") ||
                content.contains("continue") && content.length < 100
    }

    // ==================== Network Disconnect Detection ====================

    /** 网络断开错误模式（借鉴 Kilo 的 SessionNetwork.disconnected） */
    private val NETWORK_ERROR_PATTERNS = listOf(
        "ECONNRESET", "ECONNREFUSED", "ENOTFOUND", "ETIMEDOUT",
        "ENETUNREACH", "EHOSTUNREACH", "ENETDOWN",
        "load failed", "failed to fetch", "fetch failed",
        "network connection was lost", "network is unreachable",
        "socket connection", "socket hang up",
        "connection timed out", "connection terminated",
        "connect timeout", "连接超时", "网络不可达", "连接被拒绝",
    )

    /**
     * 判断错误是否为网络断开（Kilo 风格）。
     * 用于决定是否进入等待恢复模式而非直接报错。
     */
    fun isNetworkDisconnect(error: Throwable): Boolean {
        val chain = mutableListOf<String>()
        var current: Throwable? = error
        while (current != null) {
            current.message?.let { chain.add(it) }
            current = current.cause
        }
        val allMessages = chain.joinToString(" ").lowercase()
        return NETWORK_ERROR_PATTERNS.any { pattern ->
            allMessages.contains(pattern.lowercase())
        }
    }

    /**
     * 判断错误是否为网络断开（从错误消息字符串判断）。
     */
    fun isNetworkDisconnect(errorMessage: String): Boolean {
        val lower = errorMessage.lowercase()
        return NETWORK_ERROR_PATTERNS.any { lower.contains(it.lowercase()) }
    }

    // ==================== Session Fork ====================

    /**
     * 为 fork 的会话生成标题（Kilo 风格）。
     * 在原标题后追加 "(fork #N)"，支持级联 fork。
     */
    fun getForkedTitle(originalTitle: String): String {
        val regex = Regex("^(.+) \\(fork #(\\d+)\\)$")
        val match = regex.find(originalTitle)
        return if (match != null) {
            val base = match.groupValues[1]
            val num = match.groupValues[2].toInt()
            "$base (fork #${num + 1})"
        } else {
            "$originalTitle (fork #1)"
        }
    }
}
