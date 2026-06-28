package top.hsyscn.opedrgent.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchToolTest {
    @Test
    fun sanitizeQuery_preservesChinesePhrases() {
        assertEquals(
            "吉利跨时代人才跃迁计划 2026 招聘",
            sanitizeQuery("吉利跨时代人才跃迁计划 2026 招聘")
        )
        assertEquals(
            "吉利 跨时代人才跃迁计划",
            sanitizeQuery("  吉利   跨时代人才跃迁计划  ")
        )
        assertEquals(
            "吉利跨时代人才跃迁计划",
            sanitizeQuery("{\"query\":\"吉利跨时代人才跃迁计划\"}")
        )
    }
}
