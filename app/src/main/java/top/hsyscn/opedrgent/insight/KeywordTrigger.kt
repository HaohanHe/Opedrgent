package top.hsyscn.opedrgent.insight

object KeywordTrigger {
    
    val TRIGGER_KEYWORDS = listOf(
        // 中文触发词
        "发芽", "生发", "发芽报告", "知识发芽", "灵感激发",
        "深化", "联想", "洞察", "跨领域",
        "帮我深化", "来个发芽", "生发一下",
        // 英文触发词
        "insight", "sprout", "germinate", "deepen",
        "cross-domain", "震惊瞬间",
    )
    
    val FUZZY_PATTERNS = listOf(
        Regex("""帮.*?[我我].*?(?:发[芽牙]|生发|深化|联想)""", RegexOption.IGNORE_CASE),
        Regex("""(?:来|给|做).*?一个?.*?(?:发[芽牙]|报告|洞察)""", RegexOption.IGNORE_CASE),
        Regex("""(?:触发|启动|开始).*(?:发[芽牙]|insight|sprout)""", RegexOption.IGNORE_CASE),
    )
    
    fun detect(text: String): Pair<Boolean, Double> {
        if (text.isBlank()) return Pair(false, 0.0)
        
        val lowerText = text.lowercase()
        
        var maxScore = 0.0
        var triggered = false
        
        for (keyword in TRIGGER_KEYWORDS) {
            if (lowerText.contains(keyword.lowercase())) {
                triggered = true
                val score = keyword.length.toFloat() / text.length.toFloat()
                maxScore = maxOf(maxScore, score * 1.5)
            }
        }
        
        for (pattern in FUZZY_PATTERNS) {
            if (pattern.containsMatchIn(lowerText)) {
                triggered = true
                maxScore = maxOf(maxScore, 1.0)
            }
        }
        
        return Pair(triggered, maxScore.coerceIn(0.0, 1.0))
    }
    
    fun shouldAutoTrigger(text: String): Boolean {
        val (triggered, confidence) = detect(text)
        return triggered && confidence >= 0.3
    }
}
