package top.hsyscn.opedrgent.cultivation.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BaselineTemplates.STARTER 与 VirtueBaseline.isUsable 的纯 JVM 单元测试。
 * 覆盖：维度数量与名称、每维度行为非空、copy 不污染原模板、isUsable 判定。
 */
class BaselineTemplatesTest {

    @Test
    fun starterHasFourDimensionsWithExpectedNames() {
        val starter = BaselineTemplates.STARTER
        assertEquals(4, starter.size)
        assertEquals(
            setOf("尊重与倾听", "对事不对人", "情绪与措辞", "承诺与跟进"),
            starter.map { it.name }.toSet(),
        )
    }

    @Test
    fun everyDimensionHasNonBlankNameAndBehaviors() {
        BaselineTemplates.STARTER.forEach { d ->
            assertTrue(d.name.isNotBlank())
            assertTrue(d.doBehaviors.isNotEmpty())
            assertTrue(d.dontBehaviors.isNotEmpty())
            d.doBehaviors.forEach { assertTrue(it.isNotBlank()) }
            d.dontBehaviors.forEach { assertTrue(it.isNotBlank()) }
        }
    }

    @Test
    fun copyingDoesNotMutateOriginalTemplate() {
        val original = BaselineTemplates.STARTER[0].name
        val mutated = BaselineTemplates.STARTER.map { it.copy(name = "改写") }
        assertEquals("改写", mutated[0].name)
        assertEquals(original, BaselineTemplates.STARTER[0].name)
    }

    @Test
    fun starterBaselineIsUsableButEmptyIsNot() {
        assertTrue(VirtueBaseline(dimensions = BaselineTemplates.STARTER).isUsable())
        assertFalse(VirtueBaseline(dimensions = emptyList()).isUsable())
    }
}
