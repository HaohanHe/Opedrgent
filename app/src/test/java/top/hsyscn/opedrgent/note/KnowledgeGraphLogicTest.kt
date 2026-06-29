package top.hsyscn.opedrgent.note

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class KnowledgeGraphLogicTest {

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / (sqrt(normA) * sqrt(normB))).toFloat().coerceIn(0f, 1f)
    }

    @Test
    fun `cosine similarity of identical vectors is 1 dot 0`() {
        val vector = floatArrayOf(1f, 2f, 3f)
        assertEquals(1.0f, cosineSimilarity(vector, vector), 1e-6f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0 dot 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `cosine similarity of zero vector is 0 dot 0`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `cosine similarity of opposite direction vectors stays non negative`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(-1f, -2f, -3f)
        // 实现中 coerceIn(0f, 1f)，负相关应被截断为 0
        assertEquals(0.0f, cosineSimilarity(a, b), 1e-6f)
    }
}
