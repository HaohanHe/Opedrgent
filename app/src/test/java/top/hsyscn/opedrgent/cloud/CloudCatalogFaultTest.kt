package top.hsyscn.opedrgent.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [CloudCatalog] 边界与容错测试（纯 JVM）。
 *
 * 覆盖：空串 / 无 scheme / 仅主机名 / 尾随斜杠 / 路径前缀攻击（相似域名不串号）/
 * StepFun 鉴权头 / findByHost 空白大小写。
 */
class CloudCatalogFaultTest {

    @Test
    fun `空与纯空白 baseUrl 返回 null 不崩`() {
        assertNull(CloudCatalog.findByBaseUrl(""))
        assertNull(CloudCatalog.findByBaseUrl("   "))
        assertNull(CloudCatalog.findByBaseUrl("https://")) // host 为空
    }

    @Test
    fun `无 scheme 仅主机名可命中`() {
        assertSame(StepFunProvider, CloudCatalog.findByBaseUrl("api.stepfun.com"))
        assertSame(StepFunProvider, CloudCatalog.findByHost("api.stepfun.com"))
    }

    @Test
    fun `相似域名前缀不串号`() {
        // api.stepfun.com.evil.com 不应命中 StepFun（主机名不同）
        assertNull(CloudCatalog.findByBaseUrl("https://api.stepfun.com.evil.com/v1"))
        // 以 stepfun.com 结尾但主机不同
        assertNull(CloudCatalog.findByBaseUrl("https://notstepfun.com/v1"))
    }

    @Test
    fun `尾随斜杠与多斜杠路径`() {
        assertSame(
            StepFunProvider,
            CloudCatalog.findByBaseUrl("https://api.stepfun.com//step_plan//v1//"),
        )
    }

    @Test
    fun `StepFun authHeader 恒 Bearer`() {
        assertEquals("Authorization" to "Bearer k", StepFunProvider.authHeader("k"))
        assertEquals("Authorization" to "Bearer tp-xxx", StepFunProvider.authHeader("tp-xxx"))
    }

    @Test
    fun `SiliconFlow 两个集群都命中且 baseUrl 正确`() {
        assertSame(
            SiliconFlowProvider,
            CloudCatalog.findByBaseUrl("https://api.siliconflow.cn/v1"),
        )
        assertSame(
            SiliconFlowProvider,
            CloudCatalog.findByBaseUrl("https://api.siliconflow.com/v1"),
        )
        assertEquals("https://api.stepfun.com/v1", StepFunProvider.anthropicBaseUrl())
    }

    @Test
    fun `findByHost 大小写与空白`() {
        assertSame(StepFunProvider, CloudCatalog.findByHost("API.STEPFUN.COM"))
        assertSame(StepFunProvider, CloudCatalog.findByHost("  api.stepfun.com  "))
        assertNull(CloudCatalog.findByHost(""))
    }

    @Test
    fun `genericOpenAi 对非 AIza 密钥走 Bearer 且 baseUrl 原样保留`() {
        val g = CloudCatalog.genericOpenAi("https://my.endpoint/v1/")
        assertEquals("https://my.endpoint/v1/", g.baseUrl)
        assertEquals("Authorization" to "Bearer sk-xxx", g.authHeader("sk-xxx"))
    }
}
