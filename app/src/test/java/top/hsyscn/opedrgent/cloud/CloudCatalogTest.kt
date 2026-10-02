package top.hsyscn.opedrgent.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * CloudCatalog 的纯 JVM 单元测试。
 * 覆盖：findByBaseUrl 的最长路径前缀/尾斜杠/大小写、findByHost、各 provider 的 authHeader 前缀规则、
 * genericOpenAi 兜底。
 */
class CloudCatalogTest {

    @Test
    fun findByBaseUrlMatchesXiaomiPlanClusterWithAndWithoutSlash() {
        assertSame(
            XiaomiMiMoProvider,
            CloudCatalog.findByBaseUrl("https://token-plan-cn.xiaomimimo.com/v1"),
        )
        assertSame(
            XiaomiMiMoProvider,
            CloudCatalog.findByBaseUrl("https://token-plan-cn.xiaomimimo.com/v1/"),
        )
    }

    @Test
    fun findByBaseUrlMatchesStepFunPlanPath() {
        assertSame(
            StepFunProvider,
            CloudCatalog.findByBaseUrl("https://api.stepfun.com/step_plan/v1"),
        )
    }

    @Test
    fun findByBaseUrlMatchesSiliconFlowDeeperPath() {
        assertSame(
            SiliconFlowProvider,
            CloudCatalog.findByBaseUrl("https://api.siliconflow.cn/v1/chat"),
        )
    }

    @Test
    fun findByBaseUrlMatchesXiaomiPayg() {
        assertSame(
            XiaomiMiMoProvider,
            CloudCatalog.findByBaseUrl("https://api.xiaomimimo.com/v1"),
        )
    }

    @Test
    fun findByBaseUrlUnknownHostIsNull() {
        assertNull(CloudCatalog.findByBaseUrl("https://api.example.com/v1"))
    }

    @Test
    fun findByBaseUrlIsCaseInsensitive() {
        assertSame(
            StepFunProvider,
            CloudCatalog.findByBaseUrl("HTTPS://API.STEPFUN.COM/step_plan/v1"),
        )
    }

    @Test
    fun findByHostMatchesStepFun() {
        assertSame(StepFunProvider, CloudCatalog.findByHost("api.stepfun.com"))
    }

    @Test
    fun findByHostBlankOrUnknownIsNull() {
        assertNull(CloudCatalog.findByHost("   "))
        assertNull(CloudCatalog.findByHost("api.nope.com"))
    }

    @Test
    fun xiaomiAuthHeaderDependsOnKeyPrefix() {
        assertEquals("api-key" to "tp-abc", XiaomiMiMoProvider.authHeader("tp-abc"))
        assertEquals("api-key" to "ttp-abc", XiaomiMiMoProvider.authHeader("ttp-abc"))
        assertEquals("Authorization" to "Bearer sk-abc", XiaomiMiMoProvider.authHeader("sk-abc"))
        assertEquals("Authorization" to "Bearer plain", XiaomiMiMoProvider.authHeader("plain"))
    }

    @Test
    fun genericOpenAiSelectsHeaderByKeyPrefix() {
        val g = CloudCatalog.genericOpenAi("https://custom.example.com/v1")
        assertEquals("generic-openai", g.id)
        assertEquals("x-goog-api-key" to "AIzaXYZ", g.authHeader("AIzaXYZ"))
        assertEquals("Authorization" to "Bearer mykey", g.authHeader("mykey"))
    }
}
