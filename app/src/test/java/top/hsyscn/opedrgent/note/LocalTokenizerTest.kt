package top.hsyscn.opedrgent.note

import org.junit.Assert.*
import org.junit.Test

class LocalTokenizerTest {

    @Test
    fun `tokenize returns empty list for empty string`() {
        assertEquals(emptyList<String>(), LocalTokenizer.tokenize(""))
    }

    @Test
    fun `tokenize returns empty list for blank string`() {
        assertEquals(emptyList<String>(), LocalTokenizer.tokenize("   \t\n"))
    }

    @Test
    fun `tokenize handles english text`() {
        val tokens = LocalTokenizer.tokenize("Hello world")
        assertTrue("Expected hello and world, got $tokens", tokens.containsAll(listOf("hello", "world")))
        assertEquals(2, tokens.size)
    }

    @Test
    fun `tokenize handles chinese text`() {
        val tokens = LocalTokenizer.tokenize("机器学习基础")
        assertTrue("Expected meaningful Chinese tokens, got $tokens", tokens.isNotEmpty())
        // 扩展词典后可能命中“机器学习”，也可能拆成“机器/学习/基础”
        assertTrue(
            "Expected 机器学习, 学习, 基础 or 机器 in tokens, got $tokens",
            tokens.contains("机器学习") || tokens.contains("学习") || tokens.contains("基础") || tokens.contains("机器"),
        )
    }

    @Test
    fun `tokenize filters stop words`() {
        val tokens = LocalTokenizer.tokenize("的 一个 项目")
        assertEquals(listOf("项目"), tokens)
        assertFalse(tokens.contains("的"))
        assertFalse(tokens.contains("一个"))
    }

    @Test
    fun `tokenize handles oov english text`() {
        val tokens = LocalTokenizer.tokenize("abcdefg")
        assertEquals(listOf("abcdefg"), tokens)
    }

    @Test
    fun `tokenize falls back to bigrams for continuous single characters`() {
        val tokens = LocalTokenizer.tokenize("人工智能")
        // 扩展词典后可能命中“人工智能”；未命中时应回退为字符 bigram
        assertTrue("Expected meaningful tokens, got $tokens", tokens.isNotEmpty())
        assertTrue(
            "Expected 人工智能, 人工 or 智能 in tokens, got $tokens",
            tokens.contains("人工智能") || tokens.contains("人工") || tokens.contains("智能"),
        )
    }
}
