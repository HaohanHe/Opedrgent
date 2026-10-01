package top.hsyscn.opedrgent.ui.state

import top.hsyscn.opedrgent.cultivation.model.ReflectionLens

/**
 * 修炼会话统一状态机（逻辑重设计 P0）。
 *
 * 取代原先 analyzing / exemplarAnalyzing 两套平行进行态，以及各自的 violations / attempts：
 * 任意时刻只有一个阶段，批判镜与榜样镜共用同一状态机，天然杜绝“一面镜子还在跑、又点另一面”
 * 的并发态，也让 UI 不必再用一堆布尔量拼“现在到底在干嘛”。
 *
 * 边界：本类只统一“阶段”，报告结果由 CultivationUiState.result（批判镜）/ exemplarResults（榜样镜，可多位）承载；
 * 待 P1 引入统一 ReflectionReport 后，Done 将直接携带一份统一报告。
 */
sealed interface ReflectionPhase {

    /** 空闲：可以开始一次照见。 */
    data object Idle : ReflectionPhase

    /** 准备中：取理想基准、解析端侧/云端后端。 */
    data object Preparing : ReflectionPhase

    /**
     * 模型推理或工具步进行中。
     *
     * @param lens 当前是哪面镜子，用于 UI 只在对应的那面镜子按钮上呈现进度
     */
    data class Reflecting(val lens: ReflectionLens, val step: String = "") : ReflectionPhase

    /** 确定性质量门核验、必要时重做一次中。 */
    data class QualityGate(val lens: ReflectionLens) : ReflectionPhase

    /**
     * 前置条件不满足，或两次仍未过质量门：绝不输出低质结果。
     *
     * @param lens 卡在哪面镜子上
     * @param reasons 字符串级、无语义的确定性事实（缺基准 / 引用未逐字出现 / 缺替代说法等），供 UI 逐条展示
     */
    data class Blocked(
        val lens: ReflectionLens,
        val reasons: List<String>,
        val attempts: Int = 0,
    ) : ReflectionPhase
}
