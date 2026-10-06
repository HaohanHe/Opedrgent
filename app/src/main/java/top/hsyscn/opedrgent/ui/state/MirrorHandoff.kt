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

    /** 一次移交的载荷：待分析文本 + 来源标题 + 来源类型枚举（录音 / 笔记 / 洞察）。 */
    data class Payload(
        val transcript: String,
        val sourceTitle: String,
        val sourceType: SourceType,
    )

    /** 来源类型枚举：渲染处在批判镜页按类型映射到对应三语字符串，不在此携带文案。 */
    enum class SourceType { RECORDING, NOTE, INSIGHT }

    @Volatile
    private var payload: Payload? = null

    /** 来源页提交待分析文本。重复 post 会覆盖上一次未消费的移交。 */
    @Synchronized
    fun post(transcript: String, sourceTitle: String, sourceType: SourceType) {
        payload = Payload(
            transcript = transcript,
            sourceTitle = sourceTitle,
            sourceType = sourceType,
        )
    }

    /** 批判镜页进入时取出载荷并立即清空；无待移交内容时返回 null。
     *  synchronized 保证"读+清"原子，避免两个消费者并发各拿到同一份载荷（U29-11）。 */
    @Synchronized
    fun consume(): Payload? {
        val current = payload
        payload = null
        return current
    }
}
