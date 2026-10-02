package top.hsyscn.opedrgent.stt

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import top.hsyscn.opedrgent.network.LlmClient
import top.hsyscn.opedrgent.settings.ApiConfig
import kotlinx.coroutines.test.runTest

/**
 * [SmartSummaryGenerator] 纯文本解析的纯 JVM 单元测试。
 *
 * 背景耦合点（不改主代码前提下）：
 *  - SmartSummaryGenerator 内 extractJson/parseSmartSummary 为 private，不可直接单测；
 *  - LlmClient 是 final 具体类、无接口、不可子类化；
 *  - android.jar 内 org.json 为抛异常桩，故测试需引入真实 org.json。
 * 因此唯一纯 JVM 路径：用 MockWebServer 起本地 OpenAI 兼容端点，构造真实 LlmClient，
 * 让 generate() 走「HTTP → choices[0].message.content → JSON 解析」全链路。
 *
 * 覆盖：纯 JSON / ```json 围栏 / 普通 ``` 围栏；损坏 JSON→null；blank fullText→null；
 * decisions/openQuestions/people 正常解析且数组内单项损坏被跳过；metaInfo 缺省回退。
 */
class SmartSummaryParserTest {

    private lateinit var server: MockWebServer
    private lateinit var apiConfig: ApiConfig
    private lateinit var generator: SmartSummaryGenerator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // buildUrl 对以 /v1 结尾的 baseUrl 直接拼接 /chat/completions
        apiConfig = ApiConfig(
            baseUrl = server.url("/v1/").toString(),
            apiKey = "sk-test",
            model = "test-model",
        )
        generator = SmartSummaryGenerator(LlmClient(OkHttpClient.Builder().build()))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transcript(fullText: String = "今天讨论了上线计划和分工。") =
        MeetingTranscriptResult(
            fullText = fullText,
            durationMs = 19 * 60 * 1000L,
            speakers = setOf("主持人", "张三"),
        )

    /** 把一段 content 包成 OpenAI chat/completions 响应外壳并入队。 */
    private fun enqueueContent(content: String) {
        val body = """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}"""
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
    }

    @Test
    fun `纯 JSON 正常解析 decisions openQuestions people`() = runTest {
        val json = """
            {
              "decisions": [{"text": "本周四上线", "context": ["灰度无异常"]}],
              "openQuestions": [{"text": "回滚策略?", "owner": "张三"}],
              "people": [{"name": "李四", "role": "产品"}]
            }
        """.trimIndent()
        enqueueContent(json)

        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        summary!!

        assertEquals(1, summary.decisions.size)
        assertEquals("本周四上线", summary.decisions[0].text)
        assertEquals(listOf("灰度无异常"), summary.decisions[0].context)

        assertEquals(1, summary.openQuestions.size)
        assertEquals("回滚策略?", summary.openQuestions[0].text)
        assertEquals("张三", summary.openQuestions[0].owner)

        assertEquals(1, summary.people.size)
        assertEquals("李四", summary.people[0].name)
        assertEquals("产品", summary.people[0].role)

        // 确认打到了 OpenAI 兼容端点
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    @Test
    fun `json 围栏包裹可解析`() = runTest {
        val inner = """{"decisions":[{"text": "决定A"}],"people":[{"name":"王五","role":"技术"}]}"""
        enqueueContent("```json\n$inner\n```")

        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        assertEquals(1, summary!!.decisions.size)
        assertEquals("决定A", summary.decisions[0].text)
        assertEquals(1, summary.people.size)
        assertEquals("王五", summary.people[0].name)
    }

    @Test
    fun `普通反引号围栏包裹可解析`() = runTest {
        val inner = """{"openQuestions":[{"text":"待跟进问题","owner":""}]}"""
        enqueueContent("```\n$inner\n```")

        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        assertEquals(1, summary!!.openQuestions.size)
        assertEquals("待跟进问题", summary.openQuestions[0].text)
    }

    @Test
    fun `损坏 JSON 返回 null`() = runTest {
        enqueueContent("{ this is :: not valid json ,,,")
        val summary = generator.generate(transcript(), apiConfig)
        assertNull(summary)
    }

    @Test
    fun `blank fullText 返回 null 且不请求 LLM`() = runTest {
        val summary = generator.generate(transcript(fullText = "   "), apiConfig)
        assertNull(summary)
        // 提前返回，不应发出 HTTP 请求
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `decisions openQuestions people 数组内单项损坏被跳过不致整体失败`() = runTest {
        // decisions: 第 2 项是字符串（损坏），openQuestions 第 2 项是数字（损坏）
        val json = """
            {
              "decisions": [
                {"text": "决定一"},
                "我不是对象",
                {"text": "决定二"}
              ],
              "openQuestions": [
                {"text": "问题一"},
                12345
              ],
              "people": [
                {"name": "赵六", "role": ""}
              ]
            }
        """.trimIndent()
        enqueueContent(json)

        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        summary!!
        assertEquals(2, summary.decisions.size)
        assertEquals("决定一", summary.decisions[0].text)
        assertEquals("决定二", summary.decisions[1].text)
        assertEquals(1, summary.openQuestions.size)
        assertEquals("问题一", summary.openQuestions[0].text)
        assertEquals(1, summary.people.size)
        assertEquals("赵六", summary.people[0].name)
    }

    @Test
    fun `metaInfo 缺省时回退转录上下文默认值`() = runTest {
        enqueueContent("{}")
        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        summary!!
        // durationMs=19分 -> "19分0秒"；speakers.size=2；contentType 缺省 "录音笔记"
        assertEquals("19分0秒", summary.metaInfo.duration)
        assertEquals(2, summary.metaInfo.participantCount)
        assertEquals("录音笔记", summary.metaInfo.contentType)
    }
}
