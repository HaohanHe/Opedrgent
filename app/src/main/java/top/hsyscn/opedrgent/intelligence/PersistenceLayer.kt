package top.hsyscn.opedrgent.intelligence

/**
 * 向量记忆持久化层接口（抽象存储后端）。
 *
 * 定义了 VectorMemory 与存储介质之间的契约。
 * 不同实现可对应不同的持久化策略：
 * - [InMemoryPersistence]：纯内存，用于测试和开发
 * - [SqlitePersistence]：SQLite 数据库，用于生产环境持久化
 *
 * ## 设计原则
 * - 接口抽象：VectorMemory 不关心底层存储实现
 * - 可替换性：可通过依赖注入切换不同后端
 * - 线程安全：实现类需保证线程安全
 */
interface PersistenceLayer {

    /**
     * 初始化持久化层。
     *
     * 在 VectorMemory 构造时调用，用于：
     * - 创建数据库表结构
     * - 建立连接
     * - 准备资源
     */
    fun initialize()

    /**
     * 关闭持久化层。
     *
     * 释放资源、关闭连接等。
     */
    fun close()

    /**
     * 保存一条记忆向量。
     *
     * @param vector 要保存的向量数据
     * @return 是否保存成功
     */
    fun save(vector: MemoryVector): Boolean

    /**
     * 批量保存向量。
     *
     * @param vectors 向量列表
     * @return 成功保存的数量
     */
    fun saveBatch(vectors: List<MemoryVector>): Int

    /**
     * 根据 ID 获取向量。
     *
     * @param id 向量唯一标识
     * @return 找到的向量，未找到返回 null
     */
    fun getById(id: String): MemoryVector?

    /**
     * 获取所有向量（用于启动时恢复）。
     *
     * @return 所有已存储的向量列表
     */
    fun getAll(): List<MemoryVector>

    /**
     * 根据 ID 删除向量。
     *
     * @param id 要删除的向量 ID
     * @return 是否删除成功
     */
    fun deleteById(id: String): Boolean

    /**
     * 删除指定集合的所有向量。
     *
     * @param collection 集合名称
     * @return 删除的数量
     */
    fun deleteByCollection(collection: String): Int

    /**
     * 清空所有数据。
     *
     * @return 是否清空成功
     */
    fun clearAll(): Boolean

    /**
     * 获取总记录数。
     */
    fun count(): Int

    /**
     * 获取所有集合名称。
     */
    fun getCollections(): Set<String>
}
