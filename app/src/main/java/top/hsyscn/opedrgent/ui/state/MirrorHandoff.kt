package top.hsyscn.opedrgent.ui.state

/**
 * 跨页「一键送入批判镜」的最小移交载体。
 *
 * 设计约定：
 * - 仅做暂存与交接，不做任何内容判定 / 关键词扫描；
 * - 来源页（录音转写 / 笔记详情 / 洞察详情）由用户主动点击后调用 [post]；
 * - 批判镜页进入时调用 [consume] 取出并随即清空，避免下次进入时重复带入；
 * - 纯内存、进程级单例，不写库、不触发分析；分析仍由用户在批判镜页手动点「开始分析」。
 */
object MirrorHandoff {

    /** 一次移交的载荷：待分析文本 + 来源标题 + 来源类型标签（如 录音 / 笔记 / 洞察）。 */
    data class Payload(
        val transcript: String,
        val sourceTitle: String,
        val sourceTypeLabel: String,
    )

    @Volatile
    private var payload: Payload? = null

    /** 来源页提交待分析文本。重复 post 会覆盖上一次未消费的移交。 */
    fun post(transcript: String, sourceTitle: String, sourceTypeLabel: String) {
        payload = Payload(
            transcript = transcript,
            sourceTitle = sourceTitle,
            sourceTypeLabel = sourceTypeLabel,
        )
    }

    /** 批判镜页进入时取出载荷并立即清空；无待移交内容时返回 null。 */
    fun consume(): Payload? {
        val current = payload
        payload = null
        return current
    }
}
