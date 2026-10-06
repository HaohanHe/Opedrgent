package top.hsyscn.opedrgent.ui.state

/**
 * 跨页「定位到某条复盘」的最小载体：从行动项「查看复盘」进入批判镜时携带 reflectionId。
 *
 * 只传递 id，不做任何查询/判定；批判镜页进入时 [consume] 取出，切到历史区即可。
 * 本阶段不做精确滚动定位，仅作为最小定位参数预留。
 */
object ReflectionLocator {

    @Volatile
    private var reflectionId: Long? = null

    /** 从行动项查看复盘时调用。 */
    @Synchronized
    fun open(id: Long) {
        reflectionId = id
    }

    /** 批判镜页进入时取出并清空；无定位请求返回 null。
     *  synchronized 保证"读+清"原子，避免并发消费重复取到同一 id（U29-11）。 */
    @Synchronized
    fun consume(): Long? {
        val id = reflectionId
        reflectionId = null
        return id
    }
}
