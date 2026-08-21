package top.hsyscn.opedrgent.network

import org.json.JSONException
import top.hsyscn.opedrgent.utils.DebugLog
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

enum class ClassifiedErrorType {
    TIMEOUT,
    RATE_LIMIT,
    FORBIDDEN,
    CAPTCHA,
    AUTH_ERROR,
    BALANCE,
    CONTENT_FILTER,
    SSL_ERROR,
    DNS_ERROR,
    SERVER_ERROR,
    CLIENT_ERROR,
    NETWORK_ERROR,
    PARSE_ERROR,
    UNKNOWN
}

enum class RecommendedAction {
    RETRY,
    SKIP,
    DEMOTE,
    OPEN_CIRCUIT,
    FALLBACK,
    IGNORE
}

data class ClassifiedError(
    val type: ClassifiedErrorType,
    val action: RecommendedAction,
    val originalException: Exception?,
    val httpStatusCode: Int?,
    val isTransient: Boolean,
    val shouldTriggerCircuitBreaker: Boolean,
    val description: String,
    /** 服务端返回的 retry-after 延迟（毫秒），null 表示未指定 */
    val retryAfterMs: Long? = null,
)

object ErrorClassifier {

    private const val TAG = "ErrorClassifier"

    private val CAPTCHA_KEYWORDS = listOf("captcha", "challenge", "verify", "human")

    fun classify(exception: Exception): ClassifiedError {
        return classifyInternal(exception = exception, httpCode = null, responseBody = null)
    }

    fun classify(httpCode: Int, body: String? = null): ClassifiedError {
        return classifyInternal(exception = null, httpCode = httpCode, responseBody = body)
    }

    fun classify(httpCode: Int, body: String? = null, headers: Map<String, String> = emptyMap()): ClassifiedError {
        val error = classifyInternal(exception = null, httpCode = httpCode, responseBody = body)
        val retryAfter = parseRetryAfterMs(headers)
        return if (retryAfter != null) error.copy(retryAfterMs = retryAfter) else error
    }

    fun classify(exception: Exception, httpCode: Int?, responseBody: String? = null, headers: Map<String, String> = emptyMap()): ClassifiedError {
        val error = classifyInternal(exception = exception, httpCode = httpCode, responseBody = responseBody)
        val retryAfter = parseRetryAfterMs(headers)
        return if (retryAfter != null) error.copy(retryAfterMs = retryAfter) else error
    }

    fun isTransient(error: ClassifiedError): Boolean = error.isTransient

    fun shouldOpenCircuit(error: ClassifiedError): Boolean = error.shouldTriggerCircuitBreaker

    fun getRetryDelayMs(error: ClassifiedError): Long {
        // 优先使用服务端返回的 retry-after
        error.retryAfterMs?.let { return it.coerceIn(1000L, 120_000L) }
        return when (error.type) {
            ClassifiedErrorType.TIMEOUT -> 2000L
            ClassifiedErrorType.RATE_LIMIT -> 60000L
            ClassifiedErrorType.SERVER_ERROR -> 5000L
            ClassifiedErrorType.NETWORK_ERROR -> 3000L
            else -> 0L
        }
    }

    /**
     * 从 HTTP 响应头解析 retry-after 值（毫秒）。
     * 支持两种格式：
     * - retry-after: 30 （秒数）
     * - retry-after-ms: 5000 （毫秒，非标准但常见）
     */
    fun parseRetryAfterMs(headers: Map<String, String>): Long? {
        // 优先检查 retry-after-ms（毫秒精度）
        headers["retry-after-ms"]?.toLongOrNull()?.let { return it }
        // 再检查 retry-after（秒数）
        headers["retry-after"]?.let { value ->
            value.toLongOrNull()?.let { return it * 1000L }
        }
        return null
    }

    fun formatForLog(error: ClassifiedError): String {
        val codePart = error.httpStatusCode?.let { " (HTTP $it)" } ?: ""
        return "[${error.type.name}] action=${error.action.name} | ${error.description}$codePart"
    }

    private fun classifyInternal(
        exception: Exception?,
        httpCode: Int?,
        responseBody: String?
    ): ClassifiedError {

        if (exception != null) {
            val classifiedByException = classifyByException(exception)
            if (classifiedByException != null) {
                val merged = mergeWithHttpInfo(classifiedByException, httpCode, responseBody)
                DebugLog.e(formatForLog(merged))
                return merged
            }
        }

        if (httpCode != null) {
            val classifiedByHttp = classifyByHttpCode(httpCode, responseBody, exception)
            DebugLog.e(formatForLog(classifiedByHttp))
            return classifiedByHttp
        }

        val fallback = ClassifiedError(
            type = ClassifiedErrorType.UNKNOWN,
            action = RecommendedAction.IGNORE,
            originalException = exception,
            httpStatusCode = httpCode,
            isTransient = false,
            shouldTriggerCircuitBreaker = false,
            description = exception?.message ?: "Unknown error"
        )
        DebugLog.e(formatForLog(fallback), exception)
        return fallback
    }

    private fun classifyByException(exception: Exception): ClassifiedError? {
        return when (exception) {
            is SocketTimeoutException -> ClassifiedError(
                type = ClassifiedErrorType.TIMEOUT,
                action = RecommendedAction.RETRY,
                originalException = exception,
                httpStatusCode = null,
                isTransient = true,
                shouldTriggerCircuitBreaker = false,
                description = "Request timed out: ${exception.message}"
            )
            is ConnectException -> {
                val msg = (exception.message ?: "").lowercase()
                if (msg.contains("timed out") || msg.contains("timeout")) {
                    ClassifiedError(
                        type = ClassifiedErrorType.TIMEOUT,
                        action = RecommendedAction.RETRY,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = true,
                        shouldTriggerCircuitBreaker = false,
                        description = "Connection timeout: ${exception.message}"
                    )
                } else {
                    ClassifiedError(
                        type = ClassifiedErrorType.NETWORK_ERROR,
                        action = RecommendedAction.RETRY,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = true,
                        shouldTriggerCircuitBreaker = false,
                        description = "Connection failed: ${exception.message}"
                    )
                }
            }
            is UnknownHostException -> ClassifiedError(
                type = ClassifiedErrorType.DNS_ERROR,
                action = RecommendedAction.OPEN_CIRCUIT,
                originalException = exception,
                httpStatusCode = null,
                isTransient = false,
                shouldTriggerCircuitBreaker = true,
                description = "DNS resolution failed: ${exception.message}"
            )
            is SSLException, is CertificateException -> ClassifiedError(
                type = ClassifiedErrorType.SSL_ERROR,
                action = RecommendedAction.OPEN_CIRCUIT,
                originalException = exception,
                httpStatusCode = null,
                isTransient = false,
                shouldTriggerCircuitBreaker = true,
                description = "SSL/TLS error: ${exception.message}"
            )
            is JSONException -> ClassifiedError(
                type = ClassifiedErrorType.PARSE_ERROR,
                action = RecommendedAction.SKIP,
                originalException = exception,
                httpStatusCode = null,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "JSON parse error: ${exception.message}"
            )
            is java.io.IOException -> {
                val msg = (exception.message ?: "").lowercase()
                if (containsCaptchaKeyword(msg)) {
                    ClassifiedError(
                        type = ClassifiedErrorType.CAPTCHA,
                        action = RecommendedAction.OPEN_CIRCUIT,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = true,
                        description = "CAPTCHA/challenge detected in exception: ${exception.message}"
                    )
                } else {
                    ClassifiedError(
                        type = ClassifiedErrorType.NETWORK_ERROR,
                        action = RecommendedAction.RETRY,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = true,
                        shouldTriggerCircuitBreaker = false,
                        description = "Network I/O error: ${exception.message}"
                    )
                }
            }
            else -> {
                val msg = (exception.message ?: "").lowercase()
                if (containsCaptchaKeyword(msg)) {
                    ClassifiedError(
                        type = ClassifiedErrorType.CAPTCHA,
                        action = RecommendedAction.OPEN_CIRCUIT,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = true,
                        description = "CAPTCHA/challenge detected in exception message"
                    )
                } else if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("超时") || msg.contains("タイムアウト")) {
                    ClassifiedError(
                        type = ClassifiedErrorType.TIMEOUT,
                        action = RecommendedAction.RETRY,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = true,
                        shouldTriggerCircuitBreaker = false,
                        description = "Timeout: ${exception.message}"
                    )
                } else if (msg.contains("ssl") || msg.contains("certificate") || msg.contains("handshake")) {
                    ClassifiedError(
                        type = ClassifiedErrorType.SSL_ERROR,
                        action = RecommendedAction.OPEN_CIRCUIT,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = true,
                        description = "SSL/TLS error: ${exception.message}"
                    )
                } else if (msg.contains("dns") || msg.contains("resolve") || msg.contains("unknown host")) {
                    ClassifiedError(
                        type = ClassifiedErrorType.DNS_ERROR,
                        action = RecommendedAction.OPEN_CIRCUIT,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = true,
                        description = "DNS error: ${exception.message}"
                    )
                } else if (msg.contains("network") || msg.contains("connection") || msg.contains("refused")) {
                    ClassifiedError(
                        type = ClassifiedErrorType.NETWORK_ERROR,
                        action = RecommendedAction.RETRY,
                        originalException = exception,
                        httpStatusCode = null,
                        isTransient = true,
                        shouldTriggerCircuitBreaker = false,
                        description = "Network error: ${exception.message}"
                    )
                } else {
                    null
                }
            }
        }
    }

    private fun classifyByHttpCode(
        httpCode: Int,
        responseBody: String?,
        exception: Exception?
    ): ClassifiedError {
        return when (httpCode) {
            401 -> ClassifiedError(
                type = ClassifiedErrorType.AUTH_ERROR,
                action = RecommendedAction.SKIP,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "API Key 无效或已过期，请检查设置"
            )
            402 -> ClassifiedError(
                type = ClassifiedErrorType.BALANCE,
                action = RecommendedAction.SKIP,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "账户余额不足，请及时充值"
            )
            421 -> ClassifiedError(
                type = ClassifiedErrorType.CONTENT_FILTER,
                action = RecommendedAction.SKIP,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "内容被安全策略拦截"
            )
            429 -> ClassifiedError(
                type = ClassifiedErrorType.RATE_LIMIT,
                action = RecommendedAction.OPEN_CIRCUIT,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = true,
                shouldTriggerCircuitBreaker = true,
                description = "Rate limited (HTTP 429)"
            )
            403 -> {
                val bodyLower = (responseBody ?: "").lowercase()
                if (containsCaptchaKeyword(bodyLower)) {
                    ClassifiedError(
                        type = ClassifiedErrorType.CAPTCHA,
                        action = RecommendedAction.OPEN_CIRCUIT,
                        originalException = exception,
                        httpStatusCode = httpCode,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = true,
                        description = "CAPTCHA/challenge detected in HTTP 403 response"
                    )
                } else {
                    ClassifiedError(
                        type = ClassifiedErrorType.FORBIDDEN,
                        action = RecommendedAction.DEMOTE,
                        originalException = exception,
                        httpStatusCode = httpCode,
                        isTransient = false,
                        shouldTriggerCircuitBreaker = false,
                        description = "Access forbidden (HTTP 403)"
                    )
                }
            }
            in 500..599 -> ClassifiedError(
                type = ClassifiedErrorType.SERVER_ERROR,
                action = RecommendedAction.RETRY,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = true,
                shouldTriggerCircuitBreaker = true,
                description = "Server error (HTTP $httpCode)"
            )
            in 400..499 -> ClassifiedError(
                type = ClassifiedErrorType.CLIENT_ERROR,
                action = RecommendedAction.SKIP,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "Client error (HTTP $httpCode)"
            )
            else -> ClassifiedError(
                type = ClassifiedErrorType.UNKNOWN,
                action = RecommendedAction.IGNORE,
                originalException = exception,
                httpStatusCode = httpCode,
                isTransient = false,
                shouldTriggerCircuitBreaker = false,
                description = "Unexpected HTTP status: $httpCode"
            )
        }
    }

    private fun mergeWithHttpInfo(
        base: ClassifiedError,
        httpCode: Int?,
        responseBody: String?
    ): ClassifiedError {
        if (httpCode == null) return base

        if (base.type == ClassifiedErrorType.NETWORK_ERROR && httpCode == 403) {
            val bodyLower = (responseBody ?: "").lowercase()
            if (containsCaptchaKeyword(bodyLower)) {
                return base.copy(
                    type = ClassifiedErrorType.CAPTCHA,
                    action = RecommendedAction.OPEN_CIRCUIT,
                    httpStatusCode = httpCode,
                    isTransient = false,
                    shouldTriggerCircuitBreaker = true,
                    description = "CAPTCHA/challenge detected in HTTP 403 response"
                )
            }
        }

        if (base.type == ClassifiedErrorType.TIMEOUT && httpCode == 429) {
            return base.copy(
                type = ClassifiedErrorType.RATE_LIMIT,
                action = RecommendedAction.OPEN_CIRCUIT,
                httpStatusCode = httpCode,
                isTransient = true,
                shouldTriggerCircuitBreaker = true,
                description = "Rate limited after timeout (HTTP 429)"
            )
        }

        return base.copy(httpStatusCode = httpCode)
    }

    private fun containsCaptchaKeyword(text: String): Boolean {
        return CAPTCHA_KEYWORDS.any { text.contains(it) }
    }
}
