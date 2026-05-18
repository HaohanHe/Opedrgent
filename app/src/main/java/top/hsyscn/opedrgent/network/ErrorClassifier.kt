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
    val description: String
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

    fun classify(exception: Exception, httpCode: Int?, responseBody: String? = null): ClassifiedError {
        return classifyInternal(exception = exception, httpCode = httpCode, responseBody = responseBody)
    }

    fun isTransient(error: ClassifiedError): Boolean = error.isTransient

    fun shouldOpenCircuit(error: ClassifiedError): Boolean = error.shouldTriggerCircuitBreaker

    fun getRetryDelayMs(error: ClassifiedError): Long {
        return when (error.type) {
            ClassifiedErrorType.TIMEOUT -> 2000L
            ClassifiedErrorType.RATE_LIMIT -> 60000L
            ClassifiedErrorType.SERVER_ERROR -> 5000L
            ClassifiedErrorType.NETWORK_ERROR -> 3000L
            else -> 0L
        }
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
                shouldTriggerCircuitBreaker = false,
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
