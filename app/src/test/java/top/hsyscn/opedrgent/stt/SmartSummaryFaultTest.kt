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
 * [SmartSummaryGenerator] 故障注入测试（纯 JVM，走 MockWebServer 全链路）。
 *
 * 覆盖：
 *  - summarySections / chapters / quotes / actionItems 数组内单项非对象时的容错；
 *  - 超长转录文本在 buildPrompt 内被截断；
 *  - 未闭合 ``` 围栏；
 *  - 各字段类型错误（participantCount 为字符串、timestampSec 为字符串）。
 *
 * 健壮性约定：decisions/openQuestions/people 以及 summarySections/chapters/quotes/actionItems
 * 均对单项做 runCatching 隔离——数组内出现一个非对象/坏元素只跳过该项，其余合法字段正常解析，
 * 整份总结仍返回。content 子数组对非字符串元素 coerce 为字符串。
 */
class SmartSummaryFaultTest {

    private lateinit var server: MockWebServer
    private lateinit var apiConfig: ApiConfig
    private lateinit var generator: SmartSummaryGenerator

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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

    private fun transcript(fullText: String = "今天讨论了上线计划和分工。") = MeetingTranscriptResult(
        fullText = fullText,
        durationMs = 19 * 60 * 1000L,
        speakers = setOf("主持人", "张三"),
    )

    private fun enqueueContent(content: String) {
        val body = """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}"""
        server.enqueue(MockResponse().setBody(body).setResponseCode(200))
    }

    // ---------- 数组单项非对象：坏项跳过、合法项保留 ----------

    /**
     * summarySections 数组中第 2 项是字符串（非对象）。
     * 修复后单项 runCatching 隔离：跳过坏项，保留第 1、3 两个合法 section，整份总结正常返回。
     */
    @Test
    fun `summarySections 单项非对象被跳过、合法 section 保留`() = runTest {
        val json = """
            {
              "summarySections": [
                {"title": "一", "content": ["段落A"]},
                "我不是对象",
                {"title": "三", "content": ["段落C"]}
              ]
            }
        """.trimIndent()
        enqueueContent(json)
        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull("脏元素应被跳过而非丢整份总结", summary)
        // 坏项 "我不是对象" 被跳过，保留第 1、3 两个合法 section
        assertEquals(listOf("一", "三"), summary!!.summarySections.map { it.title })
        assertEquals(listOf("段落A"), summary.summarySections[0].content)
        assertEquals(listOf("段落C"), summary.summarySections[1].content)
    }

    @Test
    fun `chapters 单项非对象被跳过、合法 chapter 保留`() = runTest {
        val json = """
            {
              "chapters": [
                {"timestampSec": 10, "title": "章一", "summary": "s"},
                12345
              ]
            }
        """.trimIndent()
        enqueueContent(json)
        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        assertEquals(1, summary!!.chapters.size)
        assertEquals("章一", summary.chapters[0].title)
        assertEquals(10L, summary.chapters[0].timestampSec)
    }

    // ---------- 类型错误字段 ----------

    @Test
    fun `participantCount 为字符串时回退 speakers 大小`() = runTest {
        // optInt 抛异常 → optInt helper 捕获回退到 transcript.speakers.size=2
        enqueueContent("""{"metaInfo":{"participantCount":"三"}}""")
        val s = generator.generate(transcript(), apiConfig)
        assertNotNull(s)
        assertEquals(2, s!!.metaInfo.participantCount)
    }

    @Test
    fun `summarySections content 非字符串元素被 coerce 为字符串`() = runTest {
        // content 改用 optString(i, "")：对数字/布尔 coerce 为字符串，保留合法段落。
        val json = """
            {
              "summarySections": [
                {"title": "一", "content": ["段落A", 123]}
              ]
            }
        """.trimIndent()
        enqueueContent(json)
        val summary = generator.generate(transcript(), apiConfig)
        assertNotNull(summary)
        assertEquals(1, summary!!.summarySections.size)
        assertEquals(listOf("段落A", "123"), summary.summarySections[0].content)
    }

    // ---------- 超长转录文本 ----------

    @Test
    fun `超长转录文本不抛异常且正常解析`() = runTest {
        // 8 万字符转录，远超 8000 截断阈值
        val huge = "超长转录正文。".repeat(10_000)
        enqueueContent("""{"decisions":[{"text":"决定"}]}""")
        val s = generator.generate(transcript(fullText = huge), apiConfig)
        assertNotNull(s)
        assertEquals(1, s!!.decisions.size)
        // 确认确实发出了请求（截断后仍非空）
        assertEquals("/v1/chat/completions", server.takeRequest().path)
    }

    // ---------- 未闭合围栏 ----------

    @Test
    fun `未闭合 json 围栏退化为原始文本解析`() = runTest {
        // 只有开头 ```json 没有结尾 ```，extractJson 不截取 → 直接整段当 JSON 解析失败
        enqueueContent("```json\n{\"decisions\":[{\"text\":\"x\"}]}")
        // 行为：extractJson 找不到结尾 ```，text 保持 "```json\n{...}"
        // JSONObject 解析失败（开头有 ```json）→ null
        assertNull(generator.generate(transcript(), apiConfig))
    }

    // ---------- decisions context 数组含非字符串 ----------

    @Test
    fun `decisions context 数字元素被 coerce 为字符串`() = runTest {
        val json = """
            {
              "decisions": [{"text": "决定", "context": ["依据A", 42, true, null]}]
            }
        """.trimIndent()
        enqueueContent(json)
        val s = generator.generate(transcript(), apiConfig)
        assertNotNull(s)
        // optString(i, "") 对数字/布尔 coerce；null 元素 optString 给 ""
        val ctx = s!!.decisions[0].context
        assertEquals(listOf("依据A", "42", "true", ""), ctx)
    }
}
