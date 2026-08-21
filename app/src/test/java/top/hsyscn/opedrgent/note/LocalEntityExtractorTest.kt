package top.hsyscn.opedrgent.note

import org.junit.Assert.*
import org.junit.Test

class LocalEntityExtractorTest {

    @Test
    fun `extractEntities returns empty for blank text`() {
        assertTrue(LocalEntityExtractor.extractEntities("").isEmpty())
        assertTrue(LocalEntityExtractor.extractEntities("   ").isEmpty())
    }

    @Test
    fun `extractEntities recognizes location`() {
        val entities = LocalEntityExtractor.extractEntities("我去北京出差")
        val location = entities.find { it.type == LocalEntityExtractor.EntityType.LOCATION }
        assertNotNull("Expected a LOCATION entity", location)
        assertEquals("北京", location!!.name)
    }

    @Test
    fun `extractEntities recognizes person`() {
        val entities = LocalEntityExtractor.extractEntities("张三")
        val person = entities.find { it.type == LocalEntityExtractor.EntityType.PERSON }
        assertNotNull("Expected a PERSON entity", person)
        assertEquals("张三", person!!.name)
    }

    @Test
    fun `extractEntities recognizes concept`() {
        val entities = LocalEntityExtractor.extractEntities("机器学习")
        val concept = entities.find { it.type == LocalEntityExtractor.EntityType.CONCEPT }
        assertNotNull("Expected a CONCEPT entity", concept)
        assertEquals("机器学习", concept!!.name)
    }

    @Test
    fun `extractEntities merges overlapping entities`() {
        val entities = LocalEntityExtractor.extractEntities("北京大学")
        assertTrue("Expected ORGANIZATION entity", entities.any {
            it.type == LocalEntityExtractor.EntityType.ORGANIZATION && it.name == "北京大学"
        })
        assertFalse("Overlapping LOCATION should be removed", entities.any {
            it.type == LocalEntityExtractor.EntityType.LOCATION && it.name == "北京"
        })
    }

    @Test
    fun `extractEntities recognizes organization`() {
        val entities = LocalEntityExtractor.extractEntities("字节跳动公司发布了新产品")
        val org = entities.find { it.type == LocalEntityExtractor.EntityType.ORGANIZATION }
        assertNotNull("Expected an ORGANIZATION entity", org)
        assertEquals("字节跳动公司", org!!.name)
    }

    @Test
    fun `extractEntities recognizes time`() {
        val entities = LocalEntityExtractor.extractEntities("2024年1月1日召开会议")
        val time = entities.find { it.type == LocalEntityExtractor.EntityType.TIME }
        assertNotNull("Expected a TIME entity", time)
        assertTrue(
            "Expected time text to contain year or date, got ${time!!.name}",
            time.name.contains("2024") || time.name.contains("月"),
        )
    }

    @Test
    fun `extractEntities recognizes full date`() {
        val entities = LocalEntityExtractor.extractEntities("会议定在2024年3月5日")
        val times = entities.filter { it.type == LocalEntityExtractor.EntityType.TIME }
        assertFalse("Expected at least one TIME entity", times.isEmpty())
        assertTrue(
            "Expected time to contain year or month-day",
            times.any { it.name.contains("2024") || it.name.contains("月") },
        )
    }

    @Test
    fun `extractKeywords gives higher rank to title words also in content`() {
        val title = "项目"
        val content = "项目管理笔记"
        val keywords = LocalEntityExtractor.extractKeywords(title, content)
        assertTrue("Expected keywords not empty", keywords.isNotEmpty())
        assertEquals("项目", keywords.first())
    }

    @Test
    fun `extractKeywords filters stop words`() {
        val keywords = LocalEntityExtractor.extractKeywords("", "的 一个 项目")
        assertFalse("Stop words should be filtered", keywords.any { it in LocalTokenizer.stopWords })
        assertTrue("Expected 项目 to remain", keywords.contains("项目"))
    }

    @Test
    fun `extractKeywords deduplicates repeated tokens`() {
        val keywords = LocalEntityExtractor.extractKeywords("", "算法算法算法")
        assertEquals(listOf("算法"), keywords)
    }
}
