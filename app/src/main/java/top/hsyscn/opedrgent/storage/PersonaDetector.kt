package top.hsyscn.opedrgent.storage

/**
 * 已废除：规则式自动人格检测器。
 *
 * 旧实现按时钟（工作日/晚间/周末）、日历日程与硬编码关键词命中给 PartnerPersona 打分：
 * 调用方从未传入对话内容（内容分支实际不可达），且"看几点 + 看有没有会议"即替用户选定人格，
 * 违背"模型为大、严禁关键词命中/贴标签"的口径。现不再提供任何自动推断：
 * - 无感伙伴的使用模式由用户手动选择（见 ui/InvisiblePartnerSettings）；
 * - 对用户本人的人格画像由模型经 persona tool_calls 结合真实对话自动刻画，
 *   落 cultivation/store/PersonaProfileStore，与 actualSelf/aspiredSelf 统一，用户只读。
 *
 * 本对象仅作历史标记保留，不对外开放任何判定方法；
 * 请勿在此新增关键词词表、固定阈值或时钟/日历判定。
 */
@Deprecated(
    "规则式自动贴人格已废除，请勿再调用；使用模式由手动选择或模型 persona tool_calls 刻画。",
)
object PersonaDetector
