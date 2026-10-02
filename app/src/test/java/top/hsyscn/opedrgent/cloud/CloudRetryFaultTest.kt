package top.hsyscn.opedrgent.cloud

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * [CloudRetry] 故障注入与边界测试（纯 JVM）。
 *
 * 覆盖：
 *  - retryAfterMillis 带空白 / 小数 / 极大秒数 / 极端日期格式；
 *  - backoffMillis 极大 attempt 的移位溢出边界；
 *  - executeWithRateLimitRetry 在抛异常 block 下不吞错、原样上抛；
 *  - executeWithRateLimitRetry 并发调用互不干扰。
 */
class CloudRetryFaultTest {

    private fun responseWithRetryAfter(value: String?): Response {
        val req = Request.Builder().url("https://example.com").build()
        val b = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        if (value != null) b.header("Retry-After", value)
        return b.build()
    }

    // ---------- retryAfterMillis 边界 ----------

    @Test
    fun `Retry-After 带前后空白可解析`() {
        assertEquals(120_000L, CloudRetry.retryAfterMillis(responseWithRetryAfter("  120  ")))
    }

    @Test
    fun `Retry-After 小数秒回退 null`() {
        // toLongOrNull 不接受小数
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("0.5")))
    }

    @Test
    fun `Retry-After 极大秒数返回大毫秒`() {
        val v = CloudRetry.retryAfterMillis(responseWithRetryAfter("99999999"))
        assertTrue("expected large but was $v", v != null && v > 0)
    }

    @Test
    fun `Retry-After 乱码日期回退 null`() {
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("zzz not a date")))
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("Wed, 21 Oct"))) // 不完整
    }

    // ---------- backoffMillis 移位溢出边界 ----------

    /**
     * 修复后：attempt 极大时，移位位数先被钳制到 MAX_SHIFT_BITS，不再溢出 Long。
     * attempt=63 时本会 `500L shl 63` 溢出为 0、退化为纯抖动；
     * 现在 shift 被钳到安全范围，指数退避被统一封顶到 30000ms（再叠加抖动后仍被 30s 上限截断）。
     */
    @Test
    fun `backoff 极大 attempt 移位被钳制并封顶 30s`() {
        val v = CloudRetry.backoffMillis(63, null)
        // 修复后指数退避应被钳到 30000ms，而非退化为 [0,250) 的抖动。
        assertEquals(30_000L, v)
    }

    @Test
    fun `backoff 负 retryAfter 走指数退避`() {
        // retryAfter <= 0 视为未提供
        val v = CloudRetry.backoffMillis(0, -1L)
        assertTrue(v in 500 until 750)
    }

    // ---------- executeWithRateLimitRetry：异常透传 ----------

    @Test
    fun `block 抛异常原样上抛不被吞`() = runTest {
        val boom = RuntimeException("network down")
        var caught: Throwable? = null
        try {
            CloudRetry.executeWithRateLimitRetry<Unit>(
                isRateLimited = { false },
                retryAfterFrom = { null },
                block = { throw boom },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertEquals(boom, caught)
    }

    @Test
    fun `isRateLimited 抛异常原样上抛`() = runTest {
        val boom = IllegalStateException("judge boom")
        var caught: Throwable? = null
        try {
            CloudRetry.executeWithRateLimitRetry<Int>(
                isRateLimited = { throw boom },
                retryAfterFrom = { null },
                block = { 1 },
            )
        } catch (t: Throwable) {
            caught = t
        }
        assertEquals(boom, caught)
    }

    // ---------- 并发：多个独立重试调用互不干扰 ----------

    @Test
    fun `并发多个独立重试调用各自计数正确`() = runTest {
        val n = 4
        val calls = IntArray(n)
        // 每个协程独立：第 1 次限流，之后放行
        val results = withContext(Dispatchers.Default) {
            (0 until n).map { idx ->
                async {
                    CloudRetry.executeWithRateLimitRetry(
                        isRateLimited = { it == 1 },
                        retryAfterFrom = { null },
                        block = {
                            calls[idx] += 1
                            calls[idx]
                        },
                    )
                }
            }.map { it.await() }
        }
        results.forEachIndexed { idx, r ->
            assertEquals(2, calls[idx])
            assertEquals(2, r)
        }
    }
}
