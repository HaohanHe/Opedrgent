package top.hsyscn.opedrgent.mcp.tools

data class MiMoVoiceConfig(
    val voiceId: String = "冰糖",
    val model: MiMoModel = MiMoModel.PRESET,
)

enum class MiMoModel(val modelId: String) {
    PRESET("mimo-v2.5-tts"),
    VOICE_DESIGN("mimo-v2.5-tts-voicedesign"),
    VOICE_CLONE("mimo-v2.5-tts-voiceclone");
    
    companion object {
        fun fromId(id: String): MiMoModel = entries.firstOrNull { it.modelId == id } ?: PRESET
    }
}

data class MiMoStyleControl(
    val naturalLanguage: String? = null,
    val audioTags: List<String> = emptyList(),
    val overallStyle: String? = null,
    val isSinging: Boolean = false,
    val isDirectorMode: Boolean = false,
    val directorCharacter: String? = null,
    val directorScene: String? = null,
    val directorGuidance: String? = null,
)

data class MiMoPresetVoice(
    val name: String,
    val voiceId: String,
    val language: String,
    val gender: String,
    val style: String,
) {
    companion object {
        val ALL = listOf(
            MiMoPresetVoice("冰糖", "冰糖", "中文", "女性", "活泼少女"),
            MiMoPresetVoice("茉莉", "茉莉", "中文", "女性", "知性女声"),
            MiMoPresetVoice("苏打", "苏打", "中文", "男性", "阳光少年"),
            MiMoPresetVoice("白桦", "白桦", "中文", "男性", "成熟男声"),
            MiMoPresetVoice("Mia", "Mia", "English", "Female", "Lively girl"),
            MiMoPresetVoice("Chloe", "Chloe", "English", "Female", "Sweet Dreamy"),
            MiMoPresetVoice("Milo", "Milo", "English", "Male", "Sunny boy"),
            MiMoPresetVoice("Dean", "Dean", "English", "Male", "Steady Gentle"),
        )
        
        fun findById(id: String): MiMoPresetVoice? = ALL.firstOrNull { it.voiceId == id }
        fun findByName(name: String): MiMoPresetVoice? = ALL.firstOrNull { 
            it.name.equals(name, ignoreCase = true) 
        }
    }
}

object MiMoTTSHelper {
    
    fun buildDirectorPrompt(style: MiMoStyleControl): String {
        val sb = StringBuilder()
        
        if (style.directorCharacter != null) {
            sb.appendLine("角色：${style.directorCharacter}")
        }
        if (style.directorScene != null) {
            sb.appendLine("场景：${style.directorScene}")
        }
        if (style.directorGuidance != null) {
            sb.appendLine("指导：\n${style.directorGuidance}")
        }
        
        return sb.toString().trim()
    }
    
    fun buildAssistantContent(text: String, style: MiMoStyleControl?): String {
        val parts = mutableListOf<String>()
        
        if (style != null) {
            if (style.isSinging) {
                parts.add("(唱歌)")
            }
            
            if (style.overallStyle != null) {
                parts.add("(${style.overallStyle})")
            }

            if (style.audioTags.isNotEmpty()) {
                parts.addAll(style.audioTags.map { "[$it]" })
            }
        }

        parts.add(text)
        
        return parts.joinToString("")
    }
    
    fun suggestVoiceForText(text: String): MiMoPresetVoice? {
        val hasChinese = text.any { it.code in 0x4E00..0x9FFF }

        return if (hasChinese) {
            when {
                text.contains("可爱") || text.contains("活泼") -> MiMoPresetVoice.findById("冰糖")
                text.contains("温柔") || text.contains("知性") -> MiMoPresetVoice.findById("茉莉")
                text.contains("阳光") || text.contains("少年") -> MiMoPresetVoice.findById("苏打")
                else -> MiMoPresetVoice.findById("白桦")
            }
        } else {
            when {
                text.any { it.isUpperCase() && it.isLetter() } -> MiMoPresetVoice.findById("Chloe")
                else -> MiMoPresetVoice.findById("Mia")
            }
        }
    }
    
    fun getAvailableVoices(): List<MiMoPresetVoice> = MiMoPresetVoice.ALL
    
    fun getStyleExamples(): Map<String, List<String>> = mapOf(
        "基础情绪" to listOf("开心", "悲伤", "愤怒", "恐惧", "惊讶", "兴奋", "委屈", "平静", "冷漠"),
        "复合情绪" to listOf("怅然", "欣慰", "无奈", "愧疚", "释然", "嫉妒", "厌倦", "忐忑", "动情"),
        "整体语调" to listOf("温柔", "高冷", "活泼", "严肃", "慵懒", "俏皮", "深沉", "干练", "凌厉"),
        "音色定位" to listOf("磁性", "醇厚", "清亮", "空灵", "稚嫩", "苍老", "甜美", "沙哑", "醇雅"),
        "人设腔调" to listOf("夹子音", "御姐音", "正太音", "大叔音", "台湾腔"),
        "方言" to listOf("东北话", "四川话", "河南话", "粤语"),
        "角色扮演" to listOf("孙悟空", "林黛玉"),
        "唱歌" to listOf("唱歌"),
    )
}
