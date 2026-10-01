package top.hsyscn.opedrgent.cloud

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * 云端失败分类。口径与 [top.hsyscn.opedrgent.network.ErrorClassifier] 保持一致，
 * 但只保留「是否应降级到本地模型」所需的粗粒度语义，不与其内部类型冲突。
 */
enum class CloudFailureKind {
    /** 401/403 等鉴权失败，或 CAPTCHA/挑战 — 需要用户处理，不可自动降级 */
    AUTH,

    /** 402 余额/额度不足 — 需要用户充值或更换 Key，不可自动降级 */
    INSUFFICIENT_BALANCE,

    /** 429 限流 — 可稍后重试或降级 */
    RATE_LIMIT,

    /** 网络层错误（连接失败、DNS、SSL、IO） — 可降级 */
    NETWORK,

    /** 超时 — 可降级 */
    TIMEOUT,

    /** 5xx 服务端错误 — 可降级 */
    SERVER,

    /** 内容安全策略拦截（421 等） — 不可降级 */
    SAFETY,

    /** 其他未归类错误（400/404/未知异常等） — 不自动降级 */
    UNKNOWN,
}

data class CloudFailure(
    val kind: CloudFailureKind,
    val message: String?,
)

object CloudFallbackPolicy {

    private val CAPTCHA_KEYWORDS = listOf("captcha", "challenge", "verify", "human")

    /**
     * 把一次云端失败归类为 [CloudFailure]。
     *
     * 判定顺序与 ErrorClassifier 对齐：先按异常类型判定网络层错误，再按 HTTP 状态码判定，
     * 最后回退 UNKNOWN。允许只传其中部分信号（例如运行时只拿到错误字符串时，
     * 可用 exception = RuntimeException(错误字符串) 走消息关键词分支）。
     */
    fun classify(httpCode: Int, errorBody: String?, exception: Throwable?): CloudFailure {
        // 1) 异常分支（与 ErrorClassifier.classifyByException 同口径）
        exception?.let { ex ->
            when (ex) {
                is SocketTimeoutException ->
                    return CloudFailure(CloudFailureKind.TIMEOUT, ex.message)

                is ConnectException -> {
                    val msg = (ex.message ?: "").lowercase()
                    return if (msg.contains("timed out") || msg.contains("timeout")) {
                        CloudFailure(CloudFailureKind.TIMEOUT, ex.message)
                    } else {
                        CloudFailure(CloudFailureKind.NETWORK, ex.message)
                    }
                }

                is UnknownHostException,
                is SSLException,
                is CertificateException,
                is IOException ->
                    return CloudFailure(CloudFailureKind.NETWORK, ex.message)
            }

            // 兜底异常消息扫描（与 ErrorClassifier 未匹配具体类型时的口径一致）
            val msg = (ex.message ?: "").lowercase()
            when {
                msg.contains("timeout") || msg.contains("timed out") ||
                    msg.contains("超时") ->
                    return CloudFailure(CloudFailureKind.TIMEOUT, ex.message)
                msg.contains("ssl") || msg.contains("certificate") ||
                    msg.contains("handshake") || msg.contains("证书") ->
                    return CloudFailure(CloudFailureKind.NETWORK, ex.message)
                msg.contains("dns") || msg.contains("resolve") ||
                    msg.contains("unknown host") || msg.contains("主机") ->
                    return CloudFailure(CloudFailureKind.NETWORK, ex.message)
                msg.contains("network") || msg.contains("connection") ||
                    msg.contains("refused") || msg.contains("网络") ||
                    msg.contains("连接") ->
                    return CloudFailure(CloudFailureKind.NETWORK, ex.message)
            }
        }

        // 2) HTTP 状态码分支（与 ErrorClassifier.classifyByHttpCode 同口径）
        if (httpCode > 0) {
            when (httpCode) {
                401 -> return CloudFailure(CloudFailureKind.AUTH, errorBody)
                402 -> return CloudFailure(CloudFailureKind.INSUFFICIENT_BALANCE, errorBody)
                421 -> return CloudFailure(CloudFailureKind.SAFETY, errorBody)
                429 -> return CloudFailure(CloudFailureKind.RATE_LIMIT, errorBody)
                403 -> {
                    val bodyLower = (errorBody ?: "").lowercase()
                    return if (CAPTCHA_KEYWORDS.any { bodyLower.contains(it) }) {
                        // 挑战/验证码 — 按鉴权类处理，需用户介入
                        CloudFailure(CloudFailureKind.AUTH, errorBody)
                    } else {
                        CloudFailure(CloudFailureKind.AUTH, errorBody)
                    }
                }
                in 500..599 ->
                    return CloudFailure(CloudFailureKind.SERVER, errorBody)
                else ->
                    return CloudFailure(CloudFailureKind.UNKNOWN, errorBody)
            }
        }

        // 3) 无任何信号
        return CloudFailure(CloudFailureKind.UNKNOWN, exception?.message ?: errorBody)
    }

    /**
     * 是否允许自动降级到本地模型。
     * NETWORK / TIMEOUT / SERVER / RATE_LIMIT 为瞬时错误，可降级；
     * AUTH / INSUFFICIENT_BALANCE / SAFETY / UNKNOWN 为需要用户处理或不应静默切换的情况，不降级。
     */
    fun shouldFallbackToLocal(failure: CloudFailure): Boolean {
        return when (failure.kind) {
            CloudFailureKind.NETWORK,
            CloudFailureKind.TIMEOUT,
            CloudFailureKind.SERVER,
            CloudFailureKind.RATE_LIMIT -> true
            CloudFailureKind.AUTH,
            CloudFailureKind.INSUFFICIENT_BALANCE,
            CloudFailureKind.SAFETY,
            CloudFailureKind.UNKNOWN -> false
        }
    }
}
