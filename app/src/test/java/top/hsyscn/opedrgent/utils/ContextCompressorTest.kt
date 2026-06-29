package top.hsyscn.opedrgent.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.hsyscn.opedrgent.model.ChatMessage
import top.hsyscn.opedrgent.model.MessagePart
import top.hsyscn.opedrgent.model.Role

class ContextCompressorTest {

    @Test
    fun `estimateTokens returns 0 for empty text`() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
    }

    @Test
    fun `estimateTokens handles Chinese text`() {
        val text = "这是一段中文文本"
        val tokens = TokenEstimator.estimateTokens(text)
        assertTrue("Expected positive tokens for Chinese text", tokens > 0)
    }

    @Test
    fun `estimateMessageTokens counts text parts`() {
        val msg = ChatMessage(
            role = Role.USER,
            parts = listOf(MessagePart.Text("Hello world")),
            content = "Hello world",
        )
        val tokens = TokenEstimator.estimateMessageTokens(msg)
        assertTrue("Expected positive tokens", tokens > 0)
    }

    @Test
    fun `splitIntoTurns groups messages by user boundary`() {
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "Q1"),
            ChatMessage(role = Role.ASSISTANT, content = "A1"),
            ChatMessage(role = Role.USER, content = "Q2"),
            ChatMessage(role = Role.ASSISTANT, content = "A2"),
        )
        val turns = MessageHistoryManager.splitIntoTurns(messages)
        assertEquals(2, turns.size)
        assertEquals(2, turns[0].size)
        assertEquals(2, turns[1].size)
    }

    @Test
    fun `pruneToolOutput truncates long tool output`() {
        val longOutput = "x".repeat(1000)
        val msg = ChatMessage(
            role = Role.ASSISTANT,
            parts = listOf(MessagePart.ToolCall(
                toolName = "test",
                callId = "1",
                output = longOutput,
            )),
        )
        val pruned = MessagePruner.pruneToolOutput(msg)
        val toolCallPart = pruned.parts.filterIsInstance<MessagePart.ToolCall>().first()
        assertNotNull(toolCallPart.output)
        assertTrue("Expected truncated output", toolCallPart.output!!.length < longOutput.length)
        assertTrue("Expected truncation marker", toolCallPart.output.contains("已剪枝"))
    }

    @Test
    fun `compress returns empty result for empty messages`() = runBlocking {
        val result = ContextCompressor.compress(
            messages = emptyList(),
            systemPrompt = "system",
            maxTokens = 1000,
        )
        assertTrue(result.messages.isEmpty())
        assertNull(result.summary)
    }

    @Test
    fun `compress does not compress when under threshold`() = runBlocking {
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "Hi"),
            ChatMessage(role = Role.ASSISTANT, content = "Hello"),
        )
        val result = ContextCompressor.compress(
            messages = messages,
            systemPrompt = "system",
            maxTokens = 100000,
        )
        assertEquals(messages.size, result.messages.size)
        assertNull(result.summary)
    }

    @Test
    fun `needsCompression returns false for small context`() {
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "Hi"),
        )
        assertFalse(ContextCompressor.needsCompression(messages, "system", 100000))
    }

    @Test
    fun `compressWithTldr uses strategy without error`() = runBlocking {
        val messages = listOf(
            ChatMessage(role = Role.USER, content = "Question one"),
            ChatMessage(role = Role.ASSISTANT, content = "Answer one".repeat(100)),
            ChatMessage(role = Role.USER, content = "Question two"),
            ChatMessage(role = Role.ASSISTANT, content = "Answer two".repeat(100)),
            ChatMessage(role = Role.USER, content = "Question three"),
            ChatMessage(role = Role.ASSISTANT, content = "Answer three".repeat(100)),
        )
        val result = ContextCompressor.compressWithTldr(
            messages = messages,
            systemPrompt = "system",
            maxTokens = 100,
            strategy = CompressionStrategy.RECENT_ONLY,
            keepTurns = 1,
            generateFn = { _, _ -> "" },
        )
        assertTrue(result.messages.isNotEmpty())
    }
}
