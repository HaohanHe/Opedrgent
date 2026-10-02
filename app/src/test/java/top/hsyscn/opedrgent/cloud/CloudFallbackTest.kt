package top.hsyscn.opedrgent.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * [CloudFallbackPolicy] 的纯 JVM 单元测试。
 *
 * 覆盖：
 *  - classify 异常类型分支（SocketTimeout / Connect / UnknownHost / SSL / Certificate / IO）
 *  - 兜底异常消息关键词扫描（timeout / ssl / dns / network 及中文）
 *  - HTTP 状态码分支（401/402/403/421/429/5xx/其它）
 *  - 异常分支优先于 HTTP 码分支
 *  - 无信号回退 UNKNOWN
 *  - shouldFallbackToLocal 降级真值表
 */
class CloudFallbackTest {

    // ---------- 异常类型分支 ----------

    @Test
    fun `SocketTimeoutException 归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, SocketTimeoutException("read timed out"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `ConnectException 消息含 timed out 归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, ConnectException("connect timed out"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `ConnectException 消息含 timeout 归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, ConnectException("connect timeout"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `ConnectException 普通拒绝归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, ConnectException("Connection refused"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `UnknownHostException 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, UnknownHostException("api.example.com"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `SSLException 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, SSLException("SSL handshake aborted"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `CertificateException 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, CertificateException("unable to find valid certification path"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `普通 IOException 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, IOException("stream reset"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    // ---------- 兜底异常消息关键词扫描（RuntimeException 携带消息） ----------

    @Test
    fun `兜底消息 timeout 归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("Read timeout after 30s"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `兜底消息 timed out 归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("The operation timed out"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `兜底消息中文超时归类为 TIMEOUT`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("请求超时"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `兜底消息 ssl handshake 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("SSL handshake failure"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息证书归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("证书校验失败"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息 unknown host 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("Unknown host api.example.com"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息 dns resolve 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("DNS resolve failed"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息主机归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("主机名无法解析"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息 connection refused 归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("Connection refused by peer"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    @Test
    fun `兜底消息网络连接归类为 NETWORK`() {
        val f = CloudFallbackPolicy.classify(0, null, RuntimeException("网络连接已断开"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    // ---------- HTTP 状态码分支 ----------

    @Test
    fun `HTTP 401 归类为 AUTH`() {
        assertEquals(CloudFailureKind.AUTH, CloudFallbackPolicy.classify(401, "unauthorized", null).kind)
    }

    @Test
    fun `HTTP 402 归类为 INSUFFICIENT_BALANCE`() {
        assertEquals(CloudFailureKind.INSUFFICIENT_BALANCE, CloudFallbackPolicy.classify(402, "quota", null).kind)
    }

    @Test
    fun `HTTP 421 归类为 SAFETY`() {
        assertEquals(CloudFailureKind.SAFETY, CloudFallbackPolicy.classify(421, "blocked", null).kind)
    }

    @Test
    fun `HTTP 429 归类为 RATE_LIMIT`() {
        assertEquals(CloudFailureKind.RATE_LIMIT, CloudFallbackPolicy.classify(429, "too many", null).kind)
    }

    @Test
    fun `HTTP 403 含 captcha 关键词归类为 AUTH`() {
        val f = CloudFallbackPolicy.classify(403, "please solve captcha to continue", null)
        assertEquals(CloudFailureKind.AUTH, f.kind)
    }

    @Test
    fun `HTTP 403 无 captcha 关键词仍归类为 AUTH`() {
        val f = CloudFallbackPolicy.classify(403, "forbidden", null)
        assertEquals(CloudFailureKind.AUTH, f.kind)
    }

    @Test
    fun `HTTP 500 归类为 SERVER`() {
        assertEquals(CloudFailureKind.SERVER, CloudFallbackPolicy.classify(500, "oops", null).kind)
    }

    @Test
    fun `HTTP 503 归类为 SERVER`() {
        assertEquals(CloudFailureKind.SERVER, CloudFallbackPolicy.classify(503, "unavailable", null).kind)
    }

    @Test
    fun `HTTP 599 归类为 SERVER`() {
        assertEquals(CloudFailureKind.SERVER, CloudFallbackPolicy.classify(599, null, null).kind)
    }

    @Test
    fun `HTTP 400 归类为 UNKNOWN`() {
        assertEquals(CloudFailureKind.UNKNOWN, CloudFallbackPolicy.classify(400, "bad request", null).kind)
    }

    @Test
    fun `HTTP 404 归类为 UNKNOWN`() {
        assertEquals(CloudFailureKind.UNKNOWN, CloudFallbackPolicy.classify(404, "not found", null).kind)
    }

    @Test
    fun `HTTP 418 归类为 UNKNOWN`() {
        assertEquals(CloudFailureKind.UNKNOWN, CloudFallbackPolicy.classify(418, "teapot", null).kind)
    }

    // ---------- 无信号 / 兜底 ----------

    @Test
    fun `无任何信号归类为 UNKNOWN`() {
        val f = CloudFallbackPolicy.classify(0, null, null)
        assertEquals(CloudFailureKind.UNKNOWN, f.kind)
    }

    // ---------- 异常分支优先于 HTTP 码分支 ----------

    @Test
    fun `异常优先于 HTTP 码 TIMEOUT 覆盖 500`() {
        // 同时给 exception 与 httpCode 时，以异常为准
        val f = CloudFallbackPolicy.classify(500, "server boom", SocketTimeoutException("timeout"))
        assertEquals(CloudFailureKind.TIMEOUT, f.kind)
    }

    @Test
    fun `异常优先于 HTTP 码 NETWORK 覆盖 401`() {
        val f = CloudFallbackPolicy.classify(401, "unauthorized", ConnectException("Connection refused"))
        assertEquals(CloudFailureKind.NETWORK, f.kind)
    }

    // ---------- shouldFallbackToLocal 真值表 ----------

    private fun failure(kind: CloudFailureKind) = CloudFailure(kind, null)

    @Test
    fun `降级真值表 NETWORK TIMEOUT SERVER RATE_LIMIT 为 true`() {
        assertTrue(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.NETWORK)))
        assertTrue(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.TIMEOUT)))
        assertTrue(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.SERVER)))
        assertTrue(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.RATE_LIMIT)))
    }

    @Test
    fun `降级真值表 AUTH BALANCE SAFETY UNKNOWN 为 false`() {
        assertFalse(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.AUTH)))
        assertFalse(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.INSUFFICIENT_BALANCE)))
        assertFalse(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.SAFETY)))
        assertFalse(CloudFallbackPolicy.shouldFallbackToLocal(failure(CloudFailureKind.UNKNOWN)))
    }
}
