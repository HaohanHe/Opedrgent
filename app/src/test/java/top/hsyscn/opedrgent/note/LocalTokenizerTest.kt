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
        assertTrue("Expected 学习 in tokens, got $tokens", tokens.contains("学习"))
        // 词典未命中“基础”，应通过 bigram 或词典词覆盖核心语义
        assertTrue("Expected 基础 or 机器 in tokens, got $tokens", tokens.contains("基础") || tokens.contains("机器"))
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
        // "人工智能" 不在词典中，应回退为字符 bigram
        assertTrue("Expected bigram tokens, got $tokens", tokens.isNotEmpty())
        assertTrue("Expected 人工 in tokens, got $tokens", tokens.contains("人工"))
        assertTrue("Expected 智能 in tokens, got $tokens", tokens.contains("智能"))
    }
}
