package top.hsyscn.opedrgent.note

/**
 * 本地实体抽取器。
 *
 * 基于规则识别时间、地点、组织、人物和概念类实体，并提供关键词提取能力。
 */
object LocalEntityExtractor {

    enum class EntityType {
        PERSON, LOCATION, ORGANIZATION, TIME, CONCEPT
    }

    data class Entity(val name: String, val type: EntityType, val start: Int, val end: Int)

    private val locationNames: Set<String> = setOf(
        "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安", "南京", "重庆",
        "天津", "苏州", "长沙", "郑州", "青岛", "大连", "厦门", "哈尔滨", "长春", "沈阳",
        "济南", "合肥", "福州", "昆明", "南宁", "贵阳", "海口", "兰州", "银川", "西宁",
        "乌鲁木齐", "拉萨", "香港", "澳门", "台北",
        "中国", "美国", "日本", "韩国", "英国", "法国", "德国", "俄罗斯", "印度", "巴西",
        "加拿大", "澳大利亚",
    )

    private val orgSuffixes: Set<String> = setOf(
        "公司", "集团", "大学", "学院", "研究所", "银行", "医院", "政府", "部门", "团队",
    )

    private val relativeTimes: Set<String> = setOf(
        "今天", "昨天", "明天", "上周", "下周", "上个月", "下个月", "去年", "今年", "明年",
        "现在", "最近", "刚才", "之前", "之后", "不久",
    )

    private val conceptDictionary: Set<String> = setOf(
        "人工智能", "机器学习", "深度学习", "神经网络", "自然语言处理", "计算机视觉",
        "数据结构", "操作系统", "数据库", "网络安全", "前端开发", "后端开发",
        "产品经理", "项目管理", "团队协作", "知识管理", "用户体验", "用户界面",
        "算法设计", "软件工程", "代码审查", "版本控制", "持续集成", "持续部署",
        "微服务", "分布式系统", "云计算", "大数据", "数据挖掘", "知识图谱",
        "智能手机", "移动互联网", "电子商务", "社交媒体", "内容创作",
    )

    private val commonSurnames: Set<String> = setOf(
        "王", "李", "张", "刘", "陈", "杨", "黄", "赵", "吴", "周",
        "徐", "孙", "马", "朱", "胡", "郭", "何", "罗", "高", "林",
        "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯", "邓", "曹",
        "彭", "曾", "肖", "田", "董", "潘", "袁", "蔡", "蒋", "余",
        "于", "杜", "叶", "程", "苏", "魏", "吕", "丁", "任", "沈",
        "姚", "卢", "姜", "崔", "钟", "谭", "陆", "汪", "范", "金",
        "石", "廖", "贾", "夏", "付", "方", "白", "邹", "孟", "熊",
        "秦", "邱", "江", "尹", "薛", "闫", "段", "雷", "侯", "龙",
        "史", "黎", "贺", "顾", "毛", "郝", "龚", "邵", "万", "钱",
        "严", "覃", "武", "戴", "莫", "孔", "向", "汤", "常",
    )

    /**
     * 从文本中抽取实体。
     *
     * @return 按出现位置排序、去重后的实体列表
     */
    fun extractEntities(text: String): List<Entity> {
        if (text.isBlank()) return emptyList()
        val all = mutableListOf<Entity>()
        all.addAll(extractLocations(text))
        all.addAll(extractOrganizations(text))
        all.addAll(extractTimes(text))
        all.addAll(extractPersons(text, all))
        all.addAll(extractConcepts(text, all))
        return mergeEntities(all)
    }

    /**
     * 从标题与内容中提取关键词。
     *
     * 标题中出现的关键词权重 x3，首句中出现的关键词权重 x2。
     *
     * @return 前 20 个去重关键词
     */
    fun extractKeywords(title: String, content: String): List<String> {
        val fullText = "$title\n$content"
        val tokens = LocalTokenizer.tokenize(fullText)
            .filter { it !in LocalTokenizer.stopWords && it.length >= 2 }
        val scores = tokens.groupingBy { it }.eachCount().toMutableMap()

        val titleTokens = LocalTokenizer.tokenize(title)
            .filter { it !in LocalTokenizer.stopWords && it.length >= 2 }
            .toSet()
        val firstSentence = content.split(Regex("[。！？\n]")).firstOrNull()?.trim() ?: ""
        val firstSentenceTokens = LocalTokenizer.tokenize(firstSentence)
            .filter { it !in LocalTokenizer.stopWords && it.length >= 2 }
            .toSet()

        for (token in titleTokens) {
            scores[token] = (scores[token] ?: 0) + 3
        }
        for (token in firstSentenceTokens) {
            scores[token] = (scores[token] ?: 0) + 2
        }

        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key }
            .distinct()
            .take(20)
    }

    private fun extractLocations(text: String): List<Entity> {
        return findBySet(text, locationNames, EntityType.LOCATION)
    }

    private fun extractOrganizations(text: String): List<Entity> {
        val entities = mutableListOf<Entity>()
        val suffixPattern = orgSuffixes.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("([\\u4e00-\\u9fa5]{2,10})(?:$suffixPattern)")
        for (match in pattern.findAll(text)) {
            entities.add(Entity(match.value, EntityType.ORGANIZATION, match.range.first, match.range.last + 1))
        }
        return entities
    }

    private fun extractTimes(text: String): List<Entity> {
        val entities = mutableListOf<Entity>()
        for (match in Regex("(19\\d{2}|20\\d{2})").findAll(text)) {
            entities.add(Entity(match.value, EntityType.TIME, match.range.first, match.range.last + 1))
        }
        for (match in Regex("(\\d{1,2}月\\d{1,2}日|\\d{1,2}月)").findAll(text)) {
            entities.add(Entity(match.value, EntityType.TIME, match.range.first, match.range.last + 1))
        }
        entities.addAll(findBySet(text, relativeTimes, EntityType.TIME))
        return entities
    }

    private fun extractPersons(text: String, existing: List<Entity>): List<Entity> {
        val excludedRanges = existing
            .filter { it.type == EntityType.LOCATION || it.type == EntityType.ORGANIZATION }
            .map { it.start to it.end }
        val entities = mutableListOf<Entity>()
        for (match in Regex("([\\u4e00-\\u9fa5]{2,4})").findAll(text)) {
            val start = match.range.first
            val end = match.range.last + 1
            if (excludedRanges.any { start < it.second && end > it.first }) continue
            val name = match.value
            if (name.first().toString() in commonSurnames && !isLocationOrOrgName(name)) {
                entities.add(Entity(name, EntityType.PERSON, start, end))
            }
        }
        return entities
    }

    private fun extractConcepts(text: String, existing: List<Entity>): List<Entity> {
        val occupied = existing.map { it.start to it.end }
        val entities = mutableListOf<Entity>()
        for (concept in conceptDictionary.sortedByDescending { it.length }) {
            var start = 0
            while (true) {
                val idx = text.indexOf(concept, start)
                if (idx < 0) break
                if (occupied.none { idx < it.second && idx + concept.length > it.first }) {
                    entities.add(Entity(concept, EntityType.CONCEPT, idx, idx + concept.length))
                }
                start = idx + concept.length
            }
        }
        return entities
    }

    private fun findBySet(text: String, words: Set<String>, type: EntityType): List<Entity> {
        val entities = mutableListOf<Entity>()
        for (word in words.sortedByDescending { it.length }) {
            var start = 0
            while (true) {
                val idx = text.indexOf(word, start)
                if (idx < 0) break
                entities.add(Entity(word, type, idx, idx + word.length))
                start = idx + word.length
            }
        }
        return entities
    }

    private fun isLocationOrOrgName(name: String): Boolean {
        if (name in locationNames) return true
        for (suffix in orgSuffixes) {
            if (name.endsWith(suffix)) return true
        }
        return false
    }

    private fun mergeEntities(entities: List<Entity>): List<Entity> {
        val sorted = entities.sortedWith(
            compareByDescending<Entity> { it.end - it.start }
                .thenBy { it.start }
        )
        val result = mutableListOf<Entity>()
        for (entity in sorted) {
            val overlaps = result.any { entity.start < it.end && it.start < entity.end }
            if (!overlaps) result.add(entity)
        }
        return result.sortedBy { it.start }
    }
}
