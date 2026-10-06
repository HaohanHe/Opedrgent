package top.hsyscn.opedrgent.cultivation.model

/**
 * 自动人格画像（Auto Persona）。
 *
 * 立场：用户不需要手写任何“理想人格基准”。模型在持续观察用户本人的言行（录音转写、对话）后，
 * 自动构建并持续演进这份画像，分两层：
 * - 现实自我 [actualSelf]：从言行中观察到、且有逐字证据支撑的稳定行为模式（实然）；
 * - 理想自我 [aspiredSelf]：从用户自己的表达里推断出的价值取向与方向（应然）——依据其自我期许、
 *   反复推崇的人或原则、自我批评时流露的目标，而不是用户填写的目标。
 *
 * 铁律：
 * - 每条结论必须带逐字证据（[PersonaTrait.evidence]），证据不足就留空或放进 [openQuestions] 继续观察，绝不编造；
 * - 只描述可观察的具体行为、对事不对人，不做人格定性，也不做任何关键词命中；
 * - 画像由模型经 persona 工具写入，用户只读，无需任何配置。
 */
data class PersonaProfile(
    val id: Long = 0,
    val version: Int = 1,
    val actualSelf: List<PersonaTrait> = emptyList(),
    val aspiredSelf: List<PersonaTrait> = emptyList(),
    /** 模型尚不确定、计划在后续言行中继续验证的点；是给模型自己的备忘，不是向用户提问的表单。 */
    val openQuestions: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 画像是否仍为空（通常是刚开始使用、证据尚不足）。 */
    fun isEmpty(): Boolean = actualSelf.isEmpty() && aspiredSelf.isEmpty()
}

/**
 * 画像中的单条观察或推断。
 *
 * @param text 一条具体、可观察的行为或价值取向（对事不对人、不贴标签），如“在对方话未说完时开始回应”
 * @param evidence 支撑该条的逐字原话，必须真实出现在用户的转写中
 * @param sourceLabel 来源标签（如某次录音/复盘的标识），可空
 * @param observedAt 证据出现的时间
 * @param status 该条的演进状态
 */
data class PersonaTrait(
    val text: String,
    val evidence: String = "",
    val sourceLabel: String = "",
    val observedAt: Long = System.currentTimeMillis(),
    val status: PersonaTraitStatus = PersonaTraitStatus.ACTIVE,
)

/** 画像条目的演进状态：现行 / 已被新观察修正 / 已不再适用。 */
enum class PersonaTraitStatus {
    ACTIVE,
    SUPERSEDED,
    RETIRED;

    companion object {
        fun fromName(raw: String?): PersonaTraitStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ACTIVE
    }
}
