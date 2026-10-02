package top.hsyscn.opedrgent.cloud

import okhttp3.Interceptor
import okhttp3.Response
import top.hsyscn.opedrgent.utils.DebugLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * 429 限流重试与退避策略。
 *
 * - 最多 [MAX_ATTEMPTS] 次尝试；
 * - 优先使用服务端 Retry-After（支持秒数与 HTTP 日期两种格式）；
 * - 否则使用 500ms * 2^attempt 指数退避，叠加少量随机抖动，上限 30s。
 */
object CloudRetry {

    const val MAX_ATTEMPTS: Int = 4

    private const val TAG = "CloudRetry"
    private const val MAX_BACKOFF_MILLIS: Long = 30_000L
    private const val BASE_BACKOFF_MILLIS: Long = 500L

    /** 退避移位位数上限：避免 500L shl attempt 在 attempt 很大时溢出 Long（符号位被占用产生负值/0）。
     *  超过该位数时指数退避早已被 [MAX_BACKOFF_MILLIS] 截断，钳制在此即可。 */
    private const val MAX_SHIFT_BITS: Int = 30

    /**
     * 解析响应的 Retry-After 头，返回等待毫秒数；无法解析时返回 null。
     *
     * 支持两种标准格式：
     * - Retry-After: 120 （秒数）
     * - Retry-After: Wed, 21 Oct 2026 07:28:00 GMT （HTTP 日期）
     */
    fun retryAfterMillis(response: Response): Long? {
        val raw = response.header("Retry-After")?.trim().orEmpty()
        if (raw.isEmpty()) return null

        // 秒数形式
        raw.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return TimeUnit.SECONDS.toMillis(seconds)
            return null
        }

        // HTTP 日期形式
        return try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            val date: Date? = sdf.parse(raw)
            val delta = date?.time?.minus(System.currentTimeMillis()) ?: return null
            if (delta > 0) delta else null
        } catch (e: Exception) {
            DebugLog.w(TAG, "Retry-After 日期解析失败: $raw")
            null
        }
    }

    /**
     * 计算第 [attempt] 次尝试前应等待的毫秒数。
     * 服务端 Retry-After 优先；否则 500ms * 2^attempt 加随机抖动，上限 30s。
     */
    fun backoffMillis(attempt: Int, retryAfter: Long?): Long {
        if (retryAfter != null && retryAfter > 0) {
            return min(retryAfter, MAX_BACKOFF_MILLIS)
        }
        // attempt 很大时直接移位会溢出 Long（符号位被占用产生负值/0），先把移位位数钳制到安全范围；
        // 指数退避本就会被 30s 上限截断，无需继续放大。
        val shift = attempt.coerceIn(0, MAX_SHIFT_BITS)
        val exp = (BASE_BACKOFF_MILLIS shl shift).coerceAtMost(MAX_BACKOFF_MILLIS)
        val jitter = Random.nextLong(0, 250)
        return (exp + jitter).coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    /**
     * 以协程方式执行可能被限流的调用：当 [isRateLimited] 判定结果被限流时，
     * 按 [retryAfterFrom] 提取 Retry-After 并指数退避后重试，最多 [MAX_ATTEMPTS] 次。
     * 非限流结果或已达最大尝试次数时立即返回。
     */
    suspend fun <T> executeWithRateLimitRetry(
        isRateLimited: (T) -> Boolean,
        retryAfterFrom: (T) -> Long?,
        block: suspend (attempt: Int) -> T,
    ): T {
        var attempt = 0
        while (true) {
            val result = block(attempt)
            val limited = isRateLimited(result)
            if (!limited || attempt >= MAX_ATTEMPTS - 1) {
                return result
            }
            val waitMs = backoffMillis(attempt, retryAfterFrom(result))
            DebugLog.w(TAG, "触发限流，第 $attempt 次重试前等待 ${waitMs}ms")
            delay(waitMs)
            attempt++
        }
    }
}

/**
 * OkHttp 应用层拦截器：仅对 HTTP 429 做带退避的阻塞重试，其他状态码直接放行。
 *
 * 安全前提：
 * - SSE/流式请求的 429 在响应头阶段即可见，此时 body 尚未消费，重试安全；
 * - 仅当请求体可安全重放（无 body、非 one-shot、非 duplex 流式上传）时才重试；
 *   普通 POST JSON（toRequestBody）可重放；
 * - 达到 [CloudRetry.MAX_ATTEMPTS] 次仍为 429 时，把最后一次响应交回调用方处理。
 */
class CloudRateLimitInterceptor : Interceptor {

    private val tag: String = "CloudRateLimit"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempt = 0

        while (response.code == 429 && attempt < CloudRetry.MAX_ATTEMPTS - 1) {
            val body = request.body
            val replayable = body == null || (!body.isOneShot() && !body.isDuplex())
            if (!replayable) {
                DebugLog.w(tag, "请求体不可重放（one-shot/duplex），放弃 429 重试: ${request.url}")
                break
            }

            val waitMs = CloudRetry.backoffMillis(attempt, CloudRetry.retryAfterMillis(response))
            DebugLog.w(
                tag,
                "HTTP 429（attempt=$attempt），等待 ${waitMs}ms 后重试 ${request.url.host}${request.url.encodedPath}"
            )
            response.close()

            if (waitMs > 0) {
                Thread.sleep(waitMs)
            }
            attempt++
            response = chain.proceed(request)
        }

        return response
    }
}
