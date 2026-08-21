package top.hsyscn.opedrgent.network

import top.hsyscn.opedrgent.utils.DebugLog
import java.util.regex.Pattern

enum class QueryIntent {
    INFORMATIONAL,
    NAVIGATIONAL,
    TRANSACTIONAL
}

data class SemanticScore(
    val similarity: Double,
    val intentMatch: Double,
    val synonymCoverage: Double,
    val combinedScore: Double
)

class SemanticScorer {

    companion object {
        private const val TAG = "SemanticScorer"

        val SYNONYM_MAP: Map<String, List<String>> = mapOf(
            "手机" to listOf("智能手机", "移动电话", "手持设备", "移动终端", "mobile", "phone"),
            "ai" to listOf("人工智能", "机器学习", "深度学习", "智能算法", "artificial intelligence", "ml"),
            "人工智能" to listOf("AI", "机器学习", "深度学习", "智能系统", "machine learning", "deep learning"),
            "编程" to listOf("程序设计", "开发", "写代码", "coding", "软件开发", "programming", "develop"),
            "代码" to listOf("程序", "源码", "script", "code", "source"),
            "下载" to listOf("download", "获取", "安装包", "installer", "apk", "下载安装"),
            "官网" to listOf("官方网站", "主页", "homepage", "官方网站", "official website", "official site"),
            "教程" to listOf("指南", "入门", "教学", "tutorial", "guide", "how to", "课程", "学习"),
            "价格" to listOf("费用", "多少钱", "cost", "pricing", "收费", "报价", "价位", "价钱"),
            "购买" to listOf("买", "订购", "shop", "buy", "订单", "下单", "采购", "入手"),

            "电脑" to listOf("计算机", "笔记本", "台式机", "PC", "computer", "laptop", "desktop"),
            "软件" to listOf("应用", "app", "程序", "application", "工具", "utility", "software"),
            "系统" to listOf("操作系统", "OS", "platform", "平台", "environment"),
            "网络" to listOf("互联网", "internet", "web", "online", "联网", "在线"),
            "数据" to listOf("data", "信息", "资料", "dataset", "数据库", "database"),
            "服务器" to listOf("server", "主机", "host", "后端", "backend", "云服务", "cloud"),
            "数据库" to listOf("database", "DB", "存储", "storage", "数据仓库", "data warehouse"),
            "算法" to listOf("algorithm", "algo", "模型", "model", "逻辑", "logic"),
            "框架" to listOf("framework", "库", "library", "sdk", "工具包", "toolkit"),
            "接口" to listOf("API", "interface", "endpoint", "调用", "call"),
            "前端" to listOf("frontend", "client", "UI", "界面", "页面", "page", "网页"),
            "后端" to listOf("backend", "server-side", "服务端", "service"),
            "开发" to listOf("development", "dev", "构建", "build", "实现", "implement"),
            "测试" to listOf("test", "testing", "验证", "verify", "qa", "质量保证"),
            "部署" to listOf("deploy", "deployment", "发布", "release", "上线", "production"),
            "安全" to listOf("security", "安全防护", "加密", "encrypt", "保护", "protect"),
            "性能" to listOf("performance", "优化", "optimize", "速度", "speed", "效率", "efficiency"),
            "缓存" to listOf("cache", "caching", "缓冲", "临时存储"),
            "并发" to listOf("concurrency", "并行", "parallel", "多线程", "thread", "async", "异步"),
            "异常" to listOf("exception", "error", "错误", "bug", "故障", "fault", "issue"),
            "日志" to listOf("log", "logging", "记录", "record", "trace"),
            "配置" to listOf("config", "configuration", "设置", "setting", "options", "选项"),
            "版本" to listOf("version", "ver", "release", "更新", "update", "升级", "upgrade"),
            "文档" to listOf("document", "doc", "documentation", "说明", "manual", "参考", "reference"),
            "开源" to listOf("open source", "OSS", "免费", "free", "社区", "community"),
            "云" to listOf("cloud", "云计算", "cloud computing", "SaaS", "PaaS", "IaaS"),
            "容器" to listOf("container", "docker", "kubernetes", "k8s", "pod"),
            "微服务" to listOf("microservice", "microservices", "服务化", "service oriented"),
            "区块链" to listOf("blockchain", "链", "chain", "分布式账本", "distributed ledger"),
            "物联网" to listOf("IoT", "internet of things", "智能硬件", "smart device", "传感器", "sensor"),
            "大数据" to listOf("big data", "数据分析", "data analysis", "数据挖掘", "data mining"),
            "自动化" to listOf("automation", "自动", "auto", "脚本", "script", "批处理", "batch"),
            "虚拟化" to listOf("virtualization", "虚拟机", "VM", "virtual machine", "hypervisor"),
            "DevOps" to listOf("devops", "运维", "operations", "CI/CD", "持续集成", "持续交付"),
            "API" to listOf("接口", "application programming interface", "REST", "GraphQL", "SDK"),
            "HTTP" to listOf("http", "https", "协议", "protocol", "请求", "request", "响应", "response"),
            "JSON" to listOf("json", "数据格式", "data format", "序列化", "serialize"),
            "XML" to listOf("xml", "标记语言", "markup", "配置文件", "config file"),
            "SQL" to listOf("sql", "查询语言", "query language", "数据库查询", "database query"),
            "HTML" to listOf("html", "超文本", "hypertext", "网页标记", "markup language"),
            "CSS" to listOf("css", "样式", "style", "样式表", "stylesheet", "布局", "layout"),
            "JavaScript" to listOf("javascript", "js", "脚本语言", "scripting", "前端脚本"),
            "TypeScript" to listOf("typescript", "ts", "类型化JS", "typed js"),
            "Java" to listOf("java", "JDK", "JVM", "Spring", "Android"),
            "Kotlin" to listOf("kotlin", "kt", "JetBrains", "KMP", "Kotlin Multiplatform"),
            "Python" to listOf("python", "py", "Django", "Flask", "FastAPI"),
            "Go" to listOf("go", "golang", "Gin", "Echo"),
            "Rust" to listOf("rust", "rs", "系统编程", "systems programming"),
            "C++" to listOf("cpp", "c plus plus", "STL", "Qt"),
            "C#" to listOf("csharp", "c#", ".NET", "dotnet", "Unity"),
            "Swift" to listOf("swift", "iOS", "Apple", "Xcode"),
            "Flutter" to listOf("flutter", "dart", "跨平台", "cross platform", "移动开发"),
            "React" to listOf("react", "reactjs", "jsx", "组件", "component", "hooks"),
            "Vue" to listOf("vue", "vuejs", "渐进式框架", "progressive framework"),
            "Angular" to listOf("angular", "typescript框架", "ts framework"),
            "Node.js" to listOf("nodejs", "node", "运行时", "runtime", "npm"),
            "Linux" to listOf("linux", "unix", "shell", "bash", "命令行", "terminal", "cli"),
            "Windows" to listOf("windows", "win", "微软", "microsoft", "桌面系统"),
            "macOS" to listOf("macos", "mac", "苹果系统", "osx"),
            "Android" to listOf("android", "安卓", "谷歌", "google", "移动端", "mobile app"),
            "iOS" to listOf("ios", "苹果", "iphone", "ipad", "apple mobile"),
            "Git" to listOf("git", "版本控制", "version control", "github", "gitlab", "仓库", "repo"),
            "Docker" to listOf("docker", "容器化", "containerize", "镜像", "image"),
            "Redis" to listOf("redis", "缓存数据库", "cache db", "键值存储", "key-value"),
            "MySQL" to listOf("mysql", "关系型数据库", "rdbms", "MariaDB"),
            "PostgreSQL" to listOf("postgresql", "postgres", "pg", "对象关系型", "ORDBMS"),
            "MongoDB" to listOf("mongodb", "mongo", "NoSQL", "文档数据库", "document db"),
            "Nginx" to listOf("nginx", "反向代理", "reverse proxy", "负载均衡", "load balancer"),
            "Apache" to listOf("apache", "httpd", "Web服务器", "web server"),
            "Tomcat" to listOf("tomcat", "Servlet容器", "servlet container", "Java EE"),
            "WebSocket" to listOf("websocket", "ws", "实时通信", "real-time", "双向通信"),
            "gRPC" to listOf("grpc", "远程过程调用", "RPC", "protobuf", "Protocol Buffers"),
            "消息队列" to listOf("message queue", "MQ", "broker", "中间件", "middleware", "Kafka", "RabbitMQ", "RocketMQ"),
            "搜索引擎" to listOf("search engine", "搜索", "search", "检索", "retrieve", "elasticsearch", "Solr"),
            "推荐" to listOf("recommend", "recommendation", "个性化", "personalization", "算法推荐"),
            "自然语言处理" to listOf("NLP", "natural language processing", "文本分析", "text analysis", "语义理解"),
            "图像识别" to listOf("image recognition", "CV", "computer vision", "视觉", "vision", "OCR"),
            "语音识别" to listOf("speech recognition", "ASR", "voice", "音频", "audio", "TTS", "语音合成"),
            "机器学习" to listOf("machine learning", "ML", "训练", "train", "模型训练", "model training"),
            "深度学习" to listOf("deep learning", "DL", "神经网络", "neural network", "CNN", "RNN", "Transformer"),
            "大模型" to listOf("LLM", "large language model", "GPT", "ChatGPT", "生成式AI", "generative AI"),
            "提示词" to listOf("prompt", "prompt engineering", "指令", "instruction", "提问技巧"),
            "函数" to listOf("function", "func", "方法", "method", "子程序", "subroutine"),
            "类" to listOf("class", "类型", "type", "对象", "object", "实例", "instance"),
            "变量" to listOf("variable", "var", "声明", "declare", "定义", "define"),
            "循环" to listOf("loop", "迭代", "iterate", "遍历", "traverse", "for", "while"),
            "条件" to listOf("condition", "conditional", "判断", "if", "else", "switch", "case"),
            "数组" to listOf("array", "list", "集合", "collection", "列表", "sequence"),
            "字符串" to listOf("string", "str", "文本", "text", "字符", "char"),
            "正则" to listOf("regex", "regular expression", "regexp", "模式匹配", "pattern match"),
            "编码" to listOf("encoding", "charset", "字符集", "UTF-8", "GBK", "unicode"),
            "加密" to listOf("encryption", "cipher", "密码学", "cryptography", "hash", "哈希", "MD5", "SHA"),
            "认证" to listOf("authentication", "auth", "登录", "login", "鉴权", "authorization", "OAuth", "JWT", "token"),
            "权限" to listOf("permission", "role", "RBAC", "访问控制", "access control"),
            "会话" to listOf("session", "cookie", "状态管理", "state management"),
            "路由" to listOf("router", "routing", "路径", "path", "URL映射", "url mapping"),
            "模板" to listOf("template", "templating", "视图", "view", "渲染", "render"),
            "依赖" to listOf("dependency", "dep", "包管理", "package manager", "npm", "maven", "gradle", "pip", "cargo"),
            "构建" to listOf("build", "编译", "compile", "打包", "package", "bundle"),
            "热更新" to listOf("hot reload", "HMR", "live reload", "即时预览", "instant preview"),
            "调试" to listOf("debug", "debugging", "断点", "breakpoint", "排查", "troubleshoot"),
            "监控" to listOf("monitor", "monitoring", "观测性", "observability", "指标", "metric", "告警", "alert"),
            "限流" to listOf("rate limit", "throttle", "流量控制", "flow control", "令牌桶", "token bucket"),
            "熔断" to listOf("circuit breaker", "断路器", "降级", "degrade", "fallback"),
            "负载均衡" to listOf("load balancing", "LB", "分发", "dispatch", "调度", "schedule"),
            "高可用" to listOf("HA", "high availability", "冗余", "redundancy", "容灾", "DR", "disaster recovery"),
            "扩展性" to listOf("scalability", "伸缩", "scale", "水平扩展", "horizontal", "垂直扩展", "vertical"),
            "设计模式" to listOf("design pattern", "pattern", "单例", "singleton", "工厂", "factory", "观察者", "observer", "策略", "strategy"),
            "架构" to listOf("architecture", "结构", "structure", "分层", "layered", "模块化", "modular"),
            "重构" to listOf("refactor", "重构优化", "代码清理", "code cleanup", "技术债务", "tech debt"),
            "代码审查" to listOf("code review", "CR", "评审", "review", "PR", "merge request"),
            "单元测试" to listOf("unit test", "UT", "测试用例", "test case", "mock", "stub", "assert"),
            "集成测试" to listOf("integration test", "E2E", "端到端测试", "end-to-end", "验收测试", "acceptance test"),
            "覆盖率" to listOf("coverage", "test coverage", "代码覆盖", "code coverage"),
            "静态分析" to listOf("static analysis", "lint", "代码检查", "code inspection", "SonarQube"),
            "CI/CD" to listOf("cicd", "continuous integration", "continuous delivery", "流水线", "pipeline", "Jenkins", "GitHub Actions", "GitLab CI"),
            "问题" to listOf("issue", "problem", "缺陷", "defect", "ticket", "JIRA"),
            "需求" to listOf("requirement", "req", "功能", "feature", "用户故事", "user story", "epic"),
            "敏捷" to listOf("agile", "scrum", "看板", "kanban", "sprint", "迭代", "iteration"),
            "项目管理" to listOf("project management", "PM", "任务管理", "task management", "进度", "progress"),
            "协作" to listOf("collaboration", "协同", "cooperation", "团队", "team", "沟通", "communication"),
            "知识库" to listOf("knowledge base", "KB", "wiki", "文档中心", "doc center", "FAQ"),
            "帮助" to listOf("help", "支持", "support", "客服", "customer service", "常见问题", "faq"),
            "解决方案" to listOf("solution", "方案", "scheme", "案例", "case", "best practice", "最佳实践"),
            "对比" to listOf("compare", "comparison", "vs", "versus", "区别", "difference", "哪个好", "评测", "review"),
            "排行榜" to listOf("ranking", "list", "top10", "前十", "推荐榜", "best", "榜单"),
            "最新" to listOf("latest", "newest", "new", "最近", "recent", "2025", "2026", "今年"),
            "如何" to listOf("怎么", "怎样", "how to", "如何做", "方法", "way", "步骤", "step"),
            "为什么" to listOf("why", "原因", "reason", "原理", "principle", "机制", "mechanism"),
            "是什么" to listOf("什么是", "definition", "定义", "概念", "concept", "介绍", "intro", "简介"),
            "哪个" to listOf("哪款", "哪种", "which", "推荐", "recommend", "选择", "choose", "选哪个"),
            "好不好" to listOf("怎么样", "评价", "靠谱吗", "值得吗", "体验", "experience", "口碑", "reputation"),
            "免费" to listOf("free", "freeware", "开源", "open source", "不要钱", "0元", "白嫖"),
            "在线" to listOf("online", "web版", "网页版", "web version", "不用下载", "无需安装"),
            "安装" to listOf("install", "setup", "配置", "配置环境", "环境搭建", "环境配置"),
            "使用" to listOf("use", "usage", "用法", "usage guide", "操作", "操作指南"),
            "示例" to listOf("example", "demo", "样例", "sample", "实例", "instance", "代码示例"),
            "项目" to listOf("project", "工程", "工程代码", "source project", "github项目", "开源项目")
        )

        val NAVIGATIONAL_KEYWORDS = listOf(
            "官网", "主页", "首页", "网站", "download", "下载",
            "登录", "注册", "入口", "portal", "home", "homepage",
            "官方", "官方网站", "正式版", "正版", "授权"
        )

        val TRANSACTIONAL_KEYWORDS = listOf(
            "购买", "买", "价格", "费用", "优惠", "折扣", "促销",
            "预订", "预约", "下单", "支付", "付款", "免费", "试用",
            "多少钱", "报价", "收费", "定价", "售价", "成本",
            "优惠券", "红包", "返现", "分期", "会员", "订阅",
            "订单", "购物车", "结算", "发票", "退款", "售后"
        )

        private val STOP_WORDS = setOf(
            "的", "了", "是", "在", "这", "那", "有", "和", "与", "或",
            "但", "而", "也", "就", "都", "很", "被", "把", "让", "给",
            "从", "到", "对", "向", "为", "以", "及", "等", "中", "上",
            "下", "不", "没", "能", "可", "要", "会", "应", "该", "已",
            "再", "又", "还", "更", "最", "太", "非常", "比较", "什么",
            "怎么", "如何", "哪", "谁", "几", "多", "少", "一", "个",
            "我", "你", "他", "她", "它", "们", "这个", "那个", "一个",
            "可以", "可能", "需要", "进行", "通过", "根据", "关于", "由于",
            "如果", "虽然", "即使", "除非", "无论", "不管", "只要", "只有"
        )

        private val CHINESE_WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,4}")
        private val ENGLISH_WORD_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{1,20}")
        private val PRICE_PATTERN = Pattern.compile("[¥￥$]\\s*\\d+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?\\s*(?:元|美元|美金|块|毛)")
        private val DOMAIN_PATTERN = Pattern.compile("(?:https?://)?(?:www\\.)?[a-zA-Z0-9][-a-zA-Z0-9]*\\.(?:com|cn|org|net|io|dev|app|cc|top|xyz)")
    }

    private var queryKeywords: List<String> = emptyList()
    private var expandedKeywords: Set<String> = emptySet()
    private var detectedIntent: QueryIntent = QueryIntent.INFORMATIONAL

    fun initialize(query: String) {
        queryKeywords = extractKeywords(query)
        expandedKeywords = expandSynonyms(queryKeywords)
        detectedIntent = detectIntent(query)
        DebugLog.d("[$TAG] initialized: keywords=$queryKeywords, intent=$detectedIntent, expanded=${expandedKeywords.size} terms")
    }

    fun calculateScore(title: String, snippet: String? = null): SemanticScore {
        val combinedText = title + " " + (snippet ?: "")
        val similarity = calculateSimilarity(combinedText)
        val intentMatch = calculateIntentAlignment(title, snippet)
        val synonymCoverage = calculateKeywordCoverage(combinedText)

        val combinedScore = (similarity * 0.5 +
                intentMatch * 0.3 +
                synonymCoverage * 0.2).coerceIn(0.0, 1.0)

        val score = SemanticScore(
            similarity = similarity,
            intentMatch = intentMatch,
            synonymCoverage = synonymCoverage,
            combinedScore = combinedScore
        )
        DebugLog.d("[$TAG] score: sim=${"%.3f".format(similarity)} intent=${"%.3f".format(intentMatch)} syn=${"%.3f".format(synonymCoverage)} combined=${"%.3f".format(combinedScore)} | title=$title")
        return score
    }

    fun getDetectedIntent(): QueryIntent = detectedIntent

    fun getExpandedKeywords(): Set<String> = expandedKeywords

    private fun extractKeywords(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val words = mutableListOf<String>()

        val chineseMatcher = CHINESE_WORD_PATTERN.matcher(text)
        while (chineseMatcher.find()) {
            val word = chineseMatcher.group()
            if (word !in STOP_WORDS && word.length >= 2) {
                words.add(word)
            }
        }

        val englishMatcher = ENGLISH_WORD_PATTERN.matcher(text)
        while (englishMatcher.find()) {
            val word = englishMatcher.group().lowercase()
            if (word.lowercase() !in STOP_WORDS && word.length >= 2) {
                words.add(word)
            }
        }

        return words.distinct()
    }

    private fun expandSynonyms(keywords: List<String>): Set<String> {
        val expanded = mutableSetOf<String>()
        for (keyword in keywords) {
            expanded.add(keyword.lowercase())
            val synonyms = SYNONYM_MAP[keyword.lowercase()]
                ?: SYNONYM_MAP.entries.firstOrNull { it.key.equals(keyword, ignoreCase = true) }?.value
                ?: emptyList()
            for (syn in synonyms) {
                expanded.add(syn.lowercase())
            }
        }
        return expanded
    }

    private fun detectIntent(query: String): QueryIntent {
        val lowerQuery = query.lowercase()

        var navCount = 0
        for (kw in NAVIGATIONAL_KEYWORDS) {
            if (lowerQuery.contains(kw, ignoreCase = true)) navCount++
        }

        var transCount = 0
        for (kw in TRANSACTIONAL_KEYWORDS) {
            if (lowerQuery.contains(kw, ignoreCase = true)) transCount++
        }

        return when {
            navCount > transCount && navCount > 0 -> QueryIntent.NAVIGATIONAL
            transCount > navCount && transCount > 0 -> QueryIntent.TRANSACTIONAL
            navCount == transCount && navCount > 0 -> QueryIntent.NAVIGATIONAL
            else -> QueryIntent.INFORMATIONAL
        }
    }

    private fun calculateSimilarity(text: String): Double {
        val textWords = extractKeywords(text).map { it.lowercase() }.toSet()
        if (textWords.isEmpty() || queryKeywords.isEmpty()) return 0.0

        var matchCount = 0.0
        for (queryWord in queryKeywords) {
            val qWord = queryWord.lowercase()
            if (textWords.contains(qWord)) {
                matchCount += 1.0
                continue
            }
            val synonyms = SYNONYM_MAP[qWord]
                ?: SYNONYM_MAP.entries.firstOrNull { it.key.equals(qWord, ignoreCase = true) }?.value
                ?: emptyList()
            if (synonyms.any { syn -> textWords.any { tw -> tw.equals(syn, ignoreCase = true) } }) {
                matchCount += 0.7
            } else if (text.contains(qWord, ignoreCase = true)) {
                matchCount += 0.3
            }
        }

        return (matchCount / queryKeywords.size).coerceIn(0.0, 1.0)
    }

    private fun calculateKeywordCoverage(text: String): Double {
        val textLower = text.lowercase()
        if (expandedKeywords.isEmpty()) return 0.0

        var coverageCount = 0
        for (keyword in expandedKeywords) {
            if (textLower.contains(keyword, ignoreCase = true)) {
                coverageCount++
            }
        }
        return (coverageCount.toDouble() / expandedKeywords.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    private fun calculateIntentAlignment(title: String, snippet: String?): Double {
        return when (detectedIntent) {
            QueryIntent.NAVIGATIONAL -> calculateNavigationalAlignment(title, snippet)
            QueryIntent.TRANSACTIONAL -> calculateTransactionalAlignment(title, snippet)
            QueryIntent.INFORMATIONAL -> calculateInformationalAlignment(title, snippet)
        }
    }

    private fun calculateNavigationalAlignment(title: String, snippet: String?): Double {
        val lowerTitle = title.lowercase()
        val lowerSnippet = snippet?.lowercase() ?: ""

        if (DOMAIN_PATTERN.matcher(title).find()) return 1.0

        val officialIndicators = listOf("官方", "official", "主页", "首页", "home", "homepage", "官网", "入口", "portal")
        for (indicator in officialIndicators) {
            if (lowerTitle.contains(indicator, ignoreCase = true)) {
                return 0.85
            }
        }

        val brandIndicators = listOf("官网", "官方网站", "home", "homepage", "下载", "download", "登录", "注册")
        var brandMatches = 0
        for (ind in brandIndicators) {
            if (lowerTitle.contains(ind, ignoreCase = true) || lowerSnippet.contains(ind, ignoreCase = true)) {
                brandMatches++
            }
        }
        if (brandMatches >= 2) return 0.75
        if (brandMatches == 1) return 0.55

        val keywordOverlap = queryKeywords.count { kw -> lowerTitle.contains(kw, ignoreCase = true) }
        if (keywordOverlap > 0) return 0.4 + (keywordOverlap.toDouble() / queryKeywords.size.coerceAtLeast(1)) * 0.2

        return 0.3
    }

    private fun calculateTransactionalAlignment(title: String, snippet: String?): Double {
        val lowerTitle = title.lowercase()
        val lowerSnippet = snippet?.lowercase() ?: ""
        val combined = lowerTitle + " " + lowerSnippet

        if (PRICE_PATTERN.matcher(combined).find()) return 0.95

        val priceIndicators = listOf("价格", "费用", "多少钱", "pricing", "cost", "报价", "收费", "售价", "¥", "￥", "$", "元")
        for (ind in priceIndicators) {
            if (lowerTitle.contains(ind, ignoreCase = true)) return 0.9
            if (lowerSnippet.contains(ind, ignoreCase = true)) return 0.8
        }

        val buyIndicators = listOf("购买", "买", "订购", "buy", "shop", "order", "下单", "支付", "付款", "优惠", "折扣", "促销", "特价", "秒杀")
        var buyMatches = 0
        for (ind in buyIndicators) {
            if (combined.contains(ind, ignoreCase = true)) buyMatches++
        }
        if (buyMatches >= 2) return 0.8
        if (buyMatches == 1) return 0.65

        val trialIndicators = listOf("免费", "试用", "free", "trial", "demo", "体验版")
        for (ind in trialIndicators) {
            if (combined.contains(ind, ignoreCase = true)) return 0.7
        }

        val keywordOverlap = queryKeywords.count { kw -> lowerTitle.contains(kw, ignoreCase = true) }
        if (keywordOverlap > 0) return 0.35 + (keywordOverlap.toDouble() / queryKeywords.size.coerceAtLeast(1)) * 0.15

        return 0.25
    }

    private fun calculateInformationalAlignment(title: String, snippet: String?): Double {
        val lowerTitle = title.lowercase()
        val contentLength = (snippet?.length ?: 0) + title.length

        val keywordInTitle = queryKeywords.count { kw -> lowerTitle.contains(kw, ignoreCase = true) }
        val titleRatio = keywordInTitle.toDouble() / queryKeywords.size.coerceAtLeast(1)

        val allText = title + " " + (snippet ?: "")
        val keywordInAll = queryKeywords.count { kw -> allText.contains(kw, ignoreCase = true) }
        val allRatio = keywordInAll.toDouble() / queryKeywords.size.coerceAtLeast(1)

        val lengthScore = when {
            contentLength < 30 -> 0.3
            contentLength in 30..100 -> 0.7
            contentLength in 100..500 -> 1.0
            else -> 0.85
        }

        val infoIndicators = listOf("教程", "指南", "介绍", "详解", "原理", "是什么", "如何", "怎么", "tutorial", "guide", "intro", "入门", "基础")
        var infoBonus = 0.0
        for (ind in infoIndicators) {
            if (lowerTitle.contains(ind, ignoreCase = true)) {
                infoBonus += 0.1
            }
        }

        val baseScore = titleRatio * 0.5 + allRatio * 0.3 + lengthScore * 0.2
        return (baseScore + infoBonus).coerceIn(0.0, 1.0)
    }
}
