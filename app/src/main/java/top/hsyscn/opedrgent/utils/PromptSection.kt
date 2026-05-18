package top.hsyscn.opedrgent.utils

/**
 * PromptSection - 可缓存的提示词片段
 *
 * @param name 片段名称，用于日志和调试
 * @param content 片段内容
 * @param cacheable 是否可缓存，默认为true。为false时会被放入dynamicPart
 */
data class PromptSection(
    val name: String,
    val content: String,
    val cacheable: Boolean = true
)

/**
 * ResolvedPrompt - 解析后的提示词结果
 *
 * @param staticPart 可缓存的静态内容（cacheable=true的片段）
 * @param dynamicPart 动态内容（cacheable=false的片段）
 */
data class ResolvedPrompt(
    val staticPart: String,
    val dynamicPart: String
)

/**
 * PromptSectionResolver - 提示词片段解析器
 *
 * 将多个PromptSection按cacheable标志分组，
 * 静态内容在前，动态内容在后，中间插入缓存边界标记
 */
object PromptSectionResolver {

    const val PROMPT_CACHE_BOUNDARY = "\n---PROMPT_CACHE_BOUNDARY---\n"

    /**
     * 解析提示词片段列表
     *
     * @param sections 提示词片段列表
     * @return ResolvedPrompt 包含staticPart和dynamicPart
     */
    fun resolvePromptSections(sections: List<PromptSection>): ResolvedPrompt {
        if (sections.isEmpty()) {
            DebugLog.d("PromptSectionResolver: no sections provided, returning empty result")
            return ResolvedPrompt("", "")
        }

        val staticSections = sections.filter { it.cacheable }
        val dynamicSections = sections.filter { !it.cacheable }

        DebugLog.d("PromptSectionResolver: cache hit for ${staticSections.size} sections, " +
                "cache miss for ${dynamicSections.size} sections")

        val staticPart = staticSections.joinToString("\n\n") { it.content }.trim()
        val dynamicPart = dynamicSections.joinToString("\n\n") { it.content }.trim()

        return ResolvedPrompt(staticPart, dynamicPart)
    }
}