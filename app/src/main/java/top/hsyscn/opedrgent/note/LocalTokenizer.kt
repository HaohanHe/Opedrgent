package top.hsyscn.opedrgent.note

/**
 * 本地中文分词器。
 *
 * 基于内置词典实现正向最大匹配 + 逆向最大匹配，选择分词数更少的方案
 * （相同则取逆向）。未登录词回退为字符 bigram。结果会过滤停用词与单字。
 */
object LocalTokenizer {

    /** 内置中文词典。 */
    val dictionary: Set<String> by lazy {
        setOf(
            "学习", "工作", "项目", "笔记", "会议", "代码", "算法", "数据", "模型", "网络",
            "安全", "设计", "产品", "用户", "系统", "功能", "问题", "方法", "结果", "分析",
            "总结", "计划", "目标", "任务", "进度", "报告", "文档", "测试", "开发", "部署",
            "维护", "优化", "性能", "错误", "修复", "版本", "分支", "提交", "合并", "发布",
            "更新", "配置", "环境", "服务", "接口", "协议", "框架", "库", "工具", "平台",
            "应用", "页面", "组件", "样式", "交互", "体验", "业务", "需求", "方案", "策略",
            "流程", "规范", "标准", "质量", "效率", "成本", "风险", "决策", "沟通", "协作",
            "团队", "管理", "领导", "客户", "市场", "销售", "运营", "推广", "品牌", "内容",
            "社区", "反馈", "评价", "建议", "支持", "帮助", "指南", "教程", "案例", "实践",
            "经验", "知识", "技能", "能力", "成长", "职业", "面试", "简历", "求职", "招聘",
            "入职", "离职", "薪资", "福利", "合同", "法律", "财务", "税务", "投资", "理财",
            "保险", "房产", "健康", "运动", "饮食", "睡眠", "情绪", "心理", "关系", "家庭",
            "朋友", "旅行", "摄影", "音乐", "电影", "读书", "写作", "绘画", "游戏",
        )
    }

    private val maxDictWordLength: Int by lazy { dictionary.maxOfOrNull { it.length } ?: 4 }

    /** 停用词表。 */
    val stopWords: Set<String> by lazy {
        setOf(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人",
            "都", "一", "上", "也", "很", "到", "说", "要", "去", "你",
            "会", "着", "没有", "看", "好", "自己", "这", "他", "她", "它",
            "们", "那", "什么", "怎么", "如何", "可以", "一个", "这个", "那个", "我们",
            "你们", "他们", "她们", "它们", "这些", "那些", "这里", "那里", "这样", "那样",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "may", "might", "can", "shall", "to", "of", "in", "for", "on", "with",
            "at", "by", "from", "as", "and", "or", "but", "not", "no", "yes",
            "it", "its", "this", "that", "these", "those", "i", "you", "he", "she",
            "we", "they", "them", "him", "her", "us", "me",
        )
    }

    /**
     * 对文本进行分词。
     *
     * @return 按出现顺序排列的词语列表（保留重复，已过滤停用词和单字）
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val buffer = StringBuilder()
        var bufferIsChinese: Boolean? = null

        fun flushBuffer() {
            if (buffer.isEmpty()) return
            val segment = buffer.toString()
            when (bufferIsChinese) {
                true -> processChinese(segment, result)
                false -> processNonChinese(segment, result)
                else -> {}
            }
            buffer.clear()
            bufferIsChinese = null
        }

        for (char in text) {
            when {
                isChinese(char) -> {
                    if (bufferIsChinese == false) flushBuffer()
                    bufferIsChinese = true
                    buffer.append(char)
                }
                char.isLetterOrDigit() || char == '_' -> {
                    if (bufferIsChinese == true) flushBuffer()
                    bufferIsChinese = false
                    buffer.append(char)
                }
                else -> flushBuffer()
            }
        }
        flushBuffer()
        return result
    }

    private fun processChinese(segment: String, result: MutableList<String>) {
        if (segment.isEmpty()) return
        val forward = forwardMaxMatch(segment)
        val backward = backwardMaxMatch(segment)
        val chosen = selectSegmentation(forward, backward)

        var i = 0
        while (i < chosen.size) {
            val word = chosen[i]
            if (word.length >= 2) {
                if (word !in stopWords) result.add(word)
                i++
            } else {
                val start = i
                while (i < chosen.size && chosen[i].length == 1) i++
                val oov = chosen.subList(start, i).joinToString("")
                if (oov.length >= 2) {
                    for (bigram in generateBigrams(oov)) {
                        if (bigram !in stopWords) result.add(bigram)
                    }
                }
            }
        }
    }

    private fun processNonChinese(segment: String, result: MutableList<String>) {
        val lower = segment.lowercase()
        val parts = lower.split(Regex("[^a-zA-Z0-9_]+"))
        for (part in parts) {
            if (part.length >= 2 && part !in stopWords) result.add(part)
        }
    }

    private fun forwardMaxMatch(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var matched = false
            val maxLen = maxDictWordLength.coerceAtMost(text.length - i)
            for (len in maxLen downTo 1) {
                val word = text.substring(i, i + len)
                if (word in dictionary) {
                    result.add(word)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                result.add(text[i].toString())
                i++
            }
        }
        return result
    }

    private fun backwardMaxMatch(text: String): List<String> {
        val result = mutableListOf<String>()
        var i = text.length
        while (i > 0) {
            var matched = false
            val maxLen = maxDictWordLength.coerceAtMost(i)
            for (len in maxLen downTo 1) {
                val word = text.substring(i - len, i)
                if (word in dictionary) {
                    result.add(word)
                    i -= len
                    matched = true
                    break
                }
            }
            if (!matched) {
                result.add(text[i - 1].toString())
                i--
            }
        }
        return result.asReversed()
    }

    private fun selectSegmentation(forward: List<String>, backward: List<String>): List<String> {
        return when {
            forward.size < backward.size -> forward
            backward.size <= forward.size -> backward
            else -> forward
        }
    }

    private fun generateBigrams(text: String): List<String> {
        val result = mutableListOf<String>()
        for (i in 0..text.length - 2) {
            result.add(text.substring(i, i + 2))
        }
        return result
    }

    private fun isChinese(char: Char): Boolean = char.code in 0x4E00..0x9FFF
}
