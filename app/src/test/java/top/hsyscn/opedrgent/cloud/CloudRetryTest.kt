package top.hsyscn.opedrgent.cloud

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

/**
 * CloudRetry 的纯 JVM 单元测试。
 * 覆盖：backoffMillis 边界与抖动窗口、retryAfterMillis 秒数/HTTP 日期/非法值解析、
 * executeWithRateLimitRetry 在虚拟时间下的不限流/全限流/先限流后放行。
 */
class CloudRetryTest {

    private fun responseWithRetryAfter(value: String?): Response {
        val req = Request.Builder().url("https://example.com").build()
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
        if (value != null) builder.header("Retry-After", value)
        return builder.build()
    }

    // ===== backoffMillis =====

    @Test
    fun backoffUsesRetryAfterWhenPositive() {
        assertEquals(1000L, CloudRetry.backoffMillis(0, 1000L))
    }

    @Test
    fun backoffCapsAtThirtySeconds() {
        assertEquals(30_000L, CloudRetry.backoffMillis(0, 60_000L))
    }

    @Test
    fun backoffAttemptZeroLiesInJitterWindow() {
        val v = CloudRetry.backoffMillis(0, null)
        assertTrue("expected [500,750) but was $v", v in 500 until 750)
    }

    @Test
    fun backoffAttemptOneLiesInJitterWindow() {
        val v = CloudRetry.backoffMillis(1, null)
        assertTrue("expected [1000,1250) but was $v", v in 1000 until 1250)
    }

    @Test
    fun backoffLargeAttemptCapsAtThirtySeconds() {
        assertEquals(30_000L, CloudRetry.backoffMillis(10, null))
    }

    // ===== retryAfterMillis =====

    @Test
    fun retryAfterMissingHeaderIsNull() {
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter(null)))
    }

    @Test
    fun retryAfterSecondsParsedToMillis() {
        assertEquals(120_000L, CloudRetry.retryAfterMillis(responseWithRetryAfter("120")))
    }

    @Test
    fun retryAfterZeroIsZero() {
        assertEquals(0L, CloudRetry.retryAfterMillis(responseWithRetryAfter("0")))
    }

    @Test
    fun retryAfterNegativeIsNull() {
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("-5")))
    }

    @Test
    fun retryAfterFutureHttpDateIsPositive() {
        val v = CloudRetry.retryAfterMillis(responseWithRetryAfter("Wed, 21 Oct 2099 07:28:00 GMT"))
        assertNotNull(v)
        assertTrue("expected positive future delta but was $v", v!! > 0)
    }

    @Test
    fun retryAfterPastHttpDateIsNull() {
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("Wed, 21 Oct 2020 07:28:00 GMT")))
    }

    @Test
    fun retryAfterGarbageIsNull() {
        assertNull(CloudRetry.retryAfterMillis(responseWithRetryAfter("not-a-date")))
    }

    // ===== executeWithRateLimitRetry（虚拟时间，跳过 delay）=====

    @Test
    fun nonLimitedBlockRunsOnce() = runTest {
        var calls = 0
        val r = CloudRetry.executeWithRateLimitRetry(
            isRateLimited = { false },
            retryAfterFrom = { null },
            block = { calls += 1; 42 },
        )
        assertEquals(42, r)
        assertEquals(1, calls)
    }

    @Test
    fun alwaysLimitedRunsMaxAttemptTimesAndReturnsLast() = runTest {
        var calls = 0
        val r = CloudRetry.executeWithRateLimitRetry(
            isRateLimited = { true },
            retryAfterFrom = { null },
            block = { calls += 1; calls },
        )
        assertEquals(CloudRetry.MAX_ATTEMPTS, calls)
        assertEquals(CloudRetry.MAX_ATTEMPTS, r)
    }

    @Test
    fun limitedThenOpenRunsTwice() = runTest {
        var calls = 0
        val r = CloudRetry.executeWithRateLimitRetry(
            isRateLimited = { it == 1 },
            retryAfterFrom = { null },
            block = { calls += 1; calls },
        )
        assertEquals(2, calls)
        assertEquals(2, r)
    }
}
